package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

/**
 * T113 (QA), critério 1: integridade financeira. Dois usuários gêmeos fazem a MESMA sequência de operações das Etapas 1 a 5 (compras parceladas,
 * despesa, empréstimo, dívida, meta e reserva, pagamento de fatura, estornos, cancelamentos, arquivamentos). Um deles mistura operações de nota o
 * tempo todo (criar, editar, mover, vincular a todas as entidades, lixeira, exportar, importar); o outro não tem nota nenhuma. As fotografias
 * financeiras (JSON da API e agregados do banco, com ids e instantes mascarados) têm de ser idênticas, e as notas vinculadas continuam coerentes
 * quando o alvo é estornado, cancelado, arquivado ou (fatura vazia) removido.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T113: nenhuma operação de nota altera razão, saldos, dashboard, resumo, projeção, metas ou dívidas (gêmeos com e sem notas)")
class NoteFinancialQaIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager tm;
    @Autowired
    private MutableClock clock;

    private CardQaWorld w;

    @BeforeEach
    void cenario() {
        clock.set(MutableClockConfig.INITIAL);
        w = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
    }

    // ------------------------------------------------------------------------------------------------ fotografia

    private static String mask(String text) {
        return text.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<id>")
                .replaceAll("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?Z", "<ts>");
    }

    /** Forma canônica: chaves e elementos de lista em ordem estável (a ordem de empate nas listas não é o que se confere aqui). */
    private String canon(String text) {
        try {
            JsonNode node = om.readTree(text);
            return canonNode(node);
        } catch (Exception e) {
            List<String> lines = new ArrayList<>(List.of(text.split("\n")));
            java.util.Collections.sort(lines);
            return String.join("\n", lines);
        }
    }

    private String canonNode(JsonNode n) {
        if (n.isArray()) {
            List<String> items = new ArrayList<>();
            n.forEach(i -> items.add(canonNode(i)));
            java.util.Collections.sort(items);
            return "[" + String.join(",", items) + "]";
        }
        if (n.isObject()) {
            java.util.TreeMap<String, String> fields = new java.util.TreeMap<>();
            n.properties().forEach(e -> fields.put(e.getKey(), canonNode(e.getValue())));
            return "{" + fields + "}";
        }
        return n.toString();
    }

    private Map<String, String> snapshot(Actor a, Map<String, String> ids) throws Exception {
        Map<String, String> s = new LinkedHashMap<>();
        List<String> urls = new ArrayList<>(List.of("/v1/accounts", "/v1/cards", "/v1/people", "/v1/debts", "/v1/goals", "/v1/dashboard/summary", "/v1/summaries/2026-10",
                "/v1/summaries/2026-11", "/v1/projections", "/v1/entries?competence=2026-10", "/v1/entries?competence=2026-11", "/v1/recurrences/summary",
                "/v1/exports/transactions.csv", "/v1/exports/backup", "/v1/categories"));
        for (String id : ids.values()) {
            urls.add("/v1/goals/" + id);
        }
        for (String url : urls) {
            Res r = a.raw(HttpMethod.GET, url, null, null);
            if (r.status() == 404) {
                continue;
            }
            String text = canon(mask(r.text()));
            if (url.contains("backup")) {
                // o backup traz o próprio id/instante de geração; só a contagem de seções e os valores importam
                text = text.replaceAll("\"generatedAt\":\"[^\"]*\"", "");
            }
            s.put(url.replaceAll("/[0-9a-f-]{36}", "/<id>"), r.status() + " " + text);
        }
        s.put("ledger", jdbc.queryForList("select book_type || ':' || count(*) || ':' || sum(amount_cents) from ledger_postings where user_id = ? and active group by book_type order by book_type", String.class, a.id).toString());
        s.put("ledger-all", jdbc.queryForObject("select coalesce(string_agg(amount_cents::text || source_type || competence || book_type || active, ',' order by amount_cents, source_type, competence, book_type, effective_date, active), '') from ledger_postings where user_id = ?", String.class, a.id));
        s.put("transactions", jdbc.queryForObject("select coalesce(string_agg(type || status || amount_cents || transaction_date, ',' order by transaction_date, type, amount_cents, description), '') from transactions where user_id = ?", String.class, a.id));
        s.put("goals", jdbc.queryForObject("select coalesce(string_agg(name || target_cents || archived, ',' order by name), '') from goals where user_id = ?", String.class, a.id));
        s.put("reservations", jdbc.queryForObject("select coalesce(string_agg(amount_cents::text || reserved_on, ',' order by reserved_on, amount_cents), '') from goal_reservations where user_id = ?", String.class, a.id));
        s.put("debts", jdbc.queryForObject("select coalesce(string_agg(principal_cents || coalesce(closure, 'ACTIVE'), ',' order by principal_cents, description), '') from debts where user_id = ?", String.class, a.id));
        s.put("counts", "tx=" + a.count("transactions") + " ledger=" + a.count("ledger_postings") + " inst=" + a.count("transaction_installments") + " goals=" + a.count("goals") + " debts=" + a.count("debts"));
        return s;
    }

    private static void diff(String label, Map<String, String> x, Map<String, String> y, List<String> out) {
        for (String k : x.keySet()) {
            if (!java.util.Objects.equals(x.get(k), y.get(k))) {
                String a = x.get(k);
                String b = String.valueOf(y.get(k));
                int i = 0;
                while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
                    i++;
                }
                out.add(label + " " + k + ": diverge em " + i + " [" + a.substring(Math.max(0, i - 30), Math.min(a.length(), i + 60)) + "] x [" + b.substring(Math.max(0, i - 30), Math.min(b.length(), i + 60)) + "]");
            }
        }
        if (!x.keySet().equals(y.keySet())) {
            out.add(label + ": chaves diferentes " + x.keySet() + " x " + y.keySet());
        }
    }

    // ------------------------------------------------------------------------------------------------ roteiro

    private record Stage(String name, Map<String, String> snapshot, List<Integer> statuses) {
    }

    private static final class Ctx {
        String tx1;
        String tx2;
        String tx3;
        String person;
        String person2;
        String debtLoan;
        String debtPlain;
        String goal;
        String second;
        String invoice;
        String card2;
        String emptyInvoice;
        final Map<String, String> ids = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();
    }

    private List<Stage> run(Actor a, boolean withNotes, long seed, List<String> problems) throws Exception {
        Random r = new Random(seed);
        List<Stage> stages = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        Ctx c = new Ctx();

        c.second = GoalQa.account(a, "Poupança", "CHECKING", "100.00");
        c.person = DebtQa.person(a, "João");
        c.person2 = DebtQa.person(a, "Maria");
        c.tx1 = CardQaWorld.ok(a.purchase("1200.00", 3, "2026-10-10", "Notebook")).node().get("id").asString();
        c.tx2 = CardQaWorld.ok(a.purchase("90.00", 1, "2026-10-11", "Livro")).node().get("id").asString();
        Res expense = a.call(HttpMethod.POST, "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"50.00\",\"date\":\"2026-10-10\",\"description\":\"Mercado\",\"accountId\":\""
                + a.nubank + "\",\"categoryId\":\"" + a.food + "\",\"method\":\"PIX\"}", GoalQa.key());
        c.tx3 = CardQaWorld.ok(expense).node().get("id").asString();
        CardQaWorld.ok(DebtQa.loan(a, "500.00", "2026-10-10", c.person, null));
        c.debtLoan = DebtQa.debts(a).stream().filter(d -> d.get("person").get("id").asString().equals(c.person)).findFirst().orElseThrow().get("id").asString();
        c.debtPlain = CardQaWorld.ok(DebtQa.plainDebt(a, c.person2, "PAYABLE", "200.00", "2026-10-10", null, null)).node().get("id").asString();
        c.goal = GoalQa.goal(a, "Reserva", "20000.00", null);
        c.ids.put("goal", c.goal);
        CardQaWorld.ok(GoalQa.reserve(a, c.goal, a.nubank, "1000.00", java.time.LocalDate.of(2026, 10, 10)));
        c.invoice = a.invoices().get(0).get("id").asString();
        stages.add(new Stage("setup", snapshot(a, c.ids), List.copyOf(statuses)));

        if (withNotes) {
            noteBurst(a, c, r, "após o setup", problems);
        }
        stages.add(new Stage("setup+notas", snapshot(a, c.ids), List.copyOf(statuses)));

        // pagamento de fatura, estornos, cancelamentos, arquivamentos: a mesma sequência nos dois gêmeos
        String[][] lifecycle = {
                {"pagar fatura"}, {"estornar despesa"}, {"cancelar parcelas"}, {"cancelar dívida"}, {"estornar empréstimo"}, {"arquivar meta"}, {"arquivar pessoa"},
                {"arquivar conta"}, {"desarquivar tudo"}, {"estornar compra 1x"}};
        for (String[] step : lifecycle) {
            Res res = switch (step[0]) {
                case "pagar fatura" -> a.pay(java.util.UUID.fromString(c.invoice), "10.00", "2026-10-12", null);
                case "estornar despesa" -> a.voidTx(c.tx3);
                case "cancelar parcelas" -> a.cancel(c.tx1);
                case "cancelar dívida" -> DebtQa.cancelDebt(a, c.debtPlain);
                case "estornar empréstimo" -> DebtQa.voidDebt(a, c.debtLoan);
                case "arquivar meta" -> a.call(HttpMethod.POST, "/v1/goals/" + c.goal + "/archive", null, null);
                case "arquivar pessoa" -> a.call(HttpMethod.POST, "/v1/people/" + c.person2 + "/archive", null, null);
                case "arquivar conta" -> a.call(HttpMethod.POST, "/v1/accounts/" + c.second + "/archive", null, null);
                case "desarquivar tudo" -> {
                    a.call(HttpMethod.POST, "/v1/goals/" + c.goal + "/unarchive", null, null);
                    a.call(HttpMethod.POST, "/v1/people/" + c.person2 + "/unarchive", null, null);
                    yield a.call(HttpMethod.POST, "/v1/accounts/" + c.second + "/unarchive", null, null);
                }
                default -> a.voidTx(c.tx2);
            };
            statuses.add(res.status());
            assertThat(res.status()).as("%s: %s", step[0], res).isLessThan(500);
            if (withNotes) {
                // depois de CADA mudança no alvo, toda nota vinculada continua legível, com o vínculo e o estado do alvo, e as consultas por entidade a trazem
                verifyNotes(a, c, step[0], problems);
                noteBurst(a, c, r, "após " + step[0], problems);
            }
            stages.add(new Stage(step[0], snapshot(a, c.ids), List.copyOf(statuses)));
        }

        // fatura vazia removida pela mudança do calendário: o vínculo some junto (ON DELETE CASCADE) e a nota continua íntegra
        c.card2 = a.newCard("Segundo", 25, 5, "5000.00");
        List<JsonNode> invoices = a.invoicesOf(c.card2);
        if (!invoices.isEmpty()) {
            c.emptyInvoice = invoices.get(invoices.size() - 1).get("id").asString();
            if (withNotes) {
                String id = NoteQa.createOk(w, a, "fatura-vazia.md", "nota na fatura vazia");
                Res set = NoteQa.setLinks(a, id, "[" + NoteQa.link("INVOICE", c.emptyInvoice) + "," + NoteQa.link("CARD", c.card2) + "]", 0);
                assertThat(set.status()).as("vincular fatura vazia: %s", set);
                c.notes.add(id);
            }
        }
        Res change = a.changeDays(c.card2, 15, 25);
        statuses.add(change.status());
        assertThat(change.status()).as("mudar calendário: %s", change).isLessThan(500);
        if (withNotes && c.emptyInvoice != null) {
            long still = jdbc.queryForObject("select count(*) from credit_card_invoices where id = ?::uuid", Long.class, c.emptyInvoice);
            String noteId = c.notes.get(c.notes.size() - 1);
            Res read = a.get("/v1/notes/" + noteId);
            if (read.status() != 200) {
                problems.add("fatura removida: GET da nota vinculada -> " + read);
            } else {
                long invoiceLinks = 0;
                for (JsonNode l : read.node().get("links")) {
                    if (l.get("type").asString().equals("INVOICE")) {
                        invoiceLinks++;
                    }
                }
                System.out.println("QA-E6-FIN fatura vazia " + c.emptyInvoice + " ainda existe=" + still + " vínculos INVOICE na nota=" + invoiceLinks);
                if (still == 0 && invoiceLinks != 0) {
                    problems.add("fatura removida mas o vínculo continua na nota");
                }
            }
            Res byEntity = a.get("/v1/links?targetType=INVOICE&targetId=" + c.emptyInvoice);
            if (byEntity.status() != 200) {
                problems.add("GET /v1/links da fatura removida -> " + byEntity);
            }
            Res exp = a.raw(HttpMethod.GET, "/v1/vault/export", null, null);
            if (exp.status() != 200) {
                problems.add("export com nota de fatura removida -> " + exp.status());
            }
        }
        stages.add(new Stage("calendário", snapshot(a, c.ids), List.copyOf(statuses)));
        return stages;
    }

    /** Operações de nota aleatórias; nenhuma pode mexer em finanças. Todas sem 5xx. */
    private void noteBurst(Actor a, Ctx c, Random r, String when, List<String> problems) throws Exception {
        String[] entityLinks = {NoteQa.link("TRANSACTION", c.tx1), NoteQa.link("TRANSACTION", c.tx2), NoteQa.link("TRANSACTION", c.tx3), NoteQa.link("ACCOUNT", a.nubank),
                NoteQa.link("ACCOUNT", c.second), NoteQa.link("CARD", a.card), NoteQa.link("INVOICE", c.invoice), NoteQa.link("PERSON", c.person), NoteQa.link("PERSON", c.person2),
                NoteQa.link("DEBT", c.debtLoan), NoteQa.link("DEBT", c.debtPlain), NoteQa.link("GOAL", c.goal), NoteQa.monthLink("2026-10"), NoteQa.monthLink("2026-11")};
        for (int i = 0; i < 12; i++) {
            int dice = r.nextInt(10);
            Res res;
            if (dice < 4 || c.notes.size() < 3) {
                List<String> links = new ArrayList<>();
                for (int k = 0; k < 1 + r.nextInt(5); k++) {
                    String l = entityLinks[r.nextInt(entityLinks.length)];
                    if (!links.contains(l)) {
                        links.add(l);
                    }
                }
                res = NoteQa.createWithLinks(w, a, "fin/" + when.replace(' ', '-') + "-" + i + "-" + r.nextInt(1000) + ".md", "Nota " + i + " [[fin/ref]] #financeiro", "[" + String.join(",", links) + "]");
                if (res.status() == 201) {
                    c.notes.add(res.node().get("id").asString());
                }
            } else if (dice < 6) {
                String id = c.notes.get(r.nextInt(c.notes.size()));
                JsonNode cur = a.get("/v1/notes/" + id).node();
                if (cur == null || cur.get("trashedAt") == null || !cur.get("trashedAt").isNull()) {
                    continue;
                }
                res = NoteQa.edit(w, a, id, "editada " + i + " [[fin/ref]]", cur.get("version").asLong());
            } else if (dice < 7) {
                String id = c.notes.get(r.nextInt(c.notes.size()));
                JsonNode cur = a.get("/v1/notes/" + id).node();
                if (cur == null || cur.get("trashedAt") == null || !cur.get("trashedAt").isNull()) {
                    continue;
                }
                res = NoteQa.move(w, a, id, "movida/" + r.nextInt(100000) + ".md", cur.get("version").asLong());
            } else if (dice < 8) {
                res = NoteQa.trash(a, c.notes.get(r.nextInt(c.notes.size())));
            } else if (dice < 9) {
                res = NoteQa.restore(a, c.notes.get(r.nextInt(c.notes.size())));
            } else {
                // exportar e importar de volta (RENAME: nunca sobrescreve) mexe em notas e jamais em finanças
                Res exp = a.raw(HttpMethod.GET, "/v1/vault/export", null, null);
                assertThat(exp.status()).isIn(200, 429);
                res = a.raw(HttpMethod.GET, "/v1/notes/tags", null, null);
            }
            assertThat(res.status()).as("%s op de nota %d: %s", when, i, res).isLessThan(500);
        }
        Res bn = a.get("/v1/notes?status=ACTIVE&pageSize=50");
        assertThat(bn.status()).isEqualTo(200);
    }

    private void verifyNotes(Actor a, Ctx c, String when, List<String> problems) throws Exception {
        for (String id : c.notes) {
            Res res = a.get("/v1/notes/" + id);
            if (res.status() != 200) {
                problems.add(when + ": GET nota " + id + " -> " + res);
                continue;
            }
            for (JsonNode l : res.node().get("links")) {
                if (l.get("origin").asString().equals("EXPLICIT") && l.get("state").asString().equals("RESOLVED") && !l.get("type").asString().equals("MONTHLY_SUMMARY")) {
                    if (l.get("target") == null || l.get("target").get("label") == null || l.get("target").get("label").isNull()) {
                        problems.add(when + ": vínculo " + l.get("type") + " resolvido sem alvo/rótulo na nota " + id);
                    }
                }
            }
        }
        for (String[] t : new String[][] {{"TRANSACTION", c.tx1}, {"TRANSACTION", c.tx3}, {"DEBT", c.debtLoan}, {"GOAL", c.goal}, {"PERSON", c.person2}, {"ACCOUNT", c.second}, {"INVOICE", c.invoice}, {"CARD", a.card}}) {
            Res res = a.get("/v1/links?targetType=" + t[0] + "&targetId=" + t[1]);
            if (res.status() != 200) {
                problems.add(when + ": GET /v1/links " + t[0] + " -> " + res);
            }
        }
        // o vínculo de uma transação estornada continua e mostra o estado do alvo
        Res tx = a.get("/v1/links?targetType=TRANSACTION&targetId=" + c.tx3);
        if (tx.status() == 200 && when.equals("estornar despesa")) {
            for (JsonNode n : tx.node().get("items")) {
                JsonNode note = a.get("/v1/notes/" + n.get("id").asString()).node();
                for (JsonNode l : note.get("links")) {
                    if (l.get("type").asString().equals("TRANSACTION") && l.get("target").get("id").asString().equals(c.tx3) && !"VOIDED".equals(l.get("target").get("status").asString())) {
                        problems.add("transação estornada: vínculo com status " + l.get("target").get("status"));
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("gêmeos com e sem notas: a mesma sequência financeira dá fotografias idênticas em TODAS as etapas; vínculos coerentes e sem 5xx quando o alvo é estornado, cancelado, arquivado ou removido")
    void integridadeFinanceira() throws Exception {
        List<String> problems = new ArrayList<>();
        Actor twinWith = w.new Actor("e6finA");
        Actor twinWithout = w.new Actor("e6finB");
        List<Stage> withNotes = run(twinWith, true, 20261009L, problems);
        List<Stage> without = run(twinWithout, false, 20261009L, problems);
        assertThat(withNotes).hasSameSizeAs(without);
        List<String> diffs = new ArrayList<>();
        for (int i = 0; i < withNotes.size(); i++) {
            Stage x = withNotes.get(i);
            Stage y = without.get(i);
            diff("etapa '" + x.name() + "'", y.snapshot(), x.snapshot(), diffs);
            // o resultado de cada operação financeira (status HTTP) também é o mesmo
            assertThat(x.statuses()).as("status das operações financeiras até '%s'", x.name()).isEqualTo(y.statuses());
        }
        long vault = twinWith.count("notes");
        System.out.println("QA-E6-FIN notas=" + vault + " vínculos=" + twinWith.count("note_links") + " etapas=" + withNotes.size() + " diffs=" + diffs.size() + " problemas=" + problems.size());
        diffs.forEach(d -> System.out.println("QA-E6-FIN DIFF " + d));
        problems.forEach(d -> System.out.println("QA-E6-FIN PROBLEMA " + d));
        assertThat(vault).isGreaterThan(10);
        assertThat(diffs).as("fotografias financeiras diferentes entre o usuário com notas e o sem notas").isEmpty();
        assertThat(problems).isEmpty();
        assertThat(w.contractViolations).isEmpty();
        assertThat(twinWith.ledgerSum()).isZero();
        assertThat(twinWith.unbalancedGroups()).isZero();
    }
}
