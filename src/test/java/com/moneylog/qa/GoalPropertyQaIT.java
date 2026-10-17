package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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

@IntegrationTest
@Import(MutableClockConfig.class)
@Tag("slow")
@DisplayName("QA T98: propriedade das metas, reservas, resumo mensal e projeções misturadas com todas as operações")
class GoalPropertyQaIT {

    private static final long[] SEEDS = System.getProperty("qa.seeds") == null ? new long[] {20261008L, 5L, 777L, 31415L, 2718L}
            : java.util.Arrays.stream(System.getProperty("qa.seeds").split(",")).mapToLong(Long::parseLong).toArray();
    private static final int OPS = 220;

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

    private CardQaWorld world;

    @BeforeEach
    void cenario() {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
    }

    private LocalDate today() {
        return clock.instant().atZone(ZoneId.of("America/Sao_Paulo")).toLocalDate();
    }

    @Test
    @DisplayName("sementes fixas: razão fecha, reserva não move nada, reservado = Σ contas, projeção = projected, resumo = dashboard = lista")
    void propriedade() throws Exception {
        List<String> violations = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        int totalOps = 0;
        for (long seed : SEEDS) {
            clock.set(MutableClockConfig.INITIAL);
            Actor a = world.new Actor("e5prop" + seed);
            Random rnd = new Random(seed);
            List<String> people = new ArrayList<>();
            for (String n : List.of("João", "Maria", "Ana", "Pedro")) {
                people.add(DebtQa.person(a, n));
            }
            Map<String, Integer> statuses = new TreeMap<>();
            ctx = new Ctx();
            ctx.inv = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/accounts", "{\"name\":\"Invest\",\"type\":\"INVESTMENT\",\"institution\":\"X\","
                    + "\"openingBalance\":{\"amount\":\"5000.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}", null)).node().get("id").asString();
            ctx.second = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/accounts", "{\"name\":\"Segunda\",\"type\":\"CHECKING\",\"institution\":\"X\","
                    + "\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}", null)).node().get("id").asString();
            for (int i = 0; i < 2; i++) {
                ctx.goals.add(CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/goals", "{\"name\":\"Meta " + i + "\",\"target\":\"" + CardQaWorld.money(100_000 + rnd.nextInt(3_000_000))
                        + "\",\"deadline\":\"" + today().plusDays(30 + rnd.nextInt(600)) + "\",\"priority\":\"MEDIUM\"}", null)).node().get("id").asString());
            }
            for (int op = 0; op < OPS; op++) {
                String label = "seed " + seed + " op " + op + " (" + today() + ")";
                try {
                    String what = step(a, rnd, people, statuses, violations, label);
                    if (op % 5 == 4 || op == OPS - 1) {
                        verify(a, violations, label + " após " + what);
                    }
                } catch (AssertionError | RuntimeException e) {
                    violations.add(label + ": exceção " + e);
                }
                totalOps++;
            }
            summary.append("seed=").append(seed).append(' ').append(statuses).append('\n');
        }
        System.out.println("QA-E5-PROP ops=" + totalOps + " violations=" + violations.size() + " competências com consumo mensal negativo (reembolso em mês posterior): " + monthlyNegatives + "\n" + summary);
        violations.addAll(world.contractViolations);
        String yaml = java.nio.file.Files.readString(com.moneylog.util.ContractValidation.SPEC_FILE);
        String table = yaml.substring(yaml.indexOf("## Códigos de erro"), yaml.indexOf("Se o domínio usar outro nome"));
        Set<String> documented = new TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("`([A-Z][A-Z0-9_*]+)`").matcher(table);
        while (m.find()) {
            documented.add(m.group(1));
        }
        for (String code : world.seenCodes) {
            boolean ok = documented.contains(code) || documented.stream().filter(d -> d.startsWith("*")).anyMatch(d -> code.endsWith(d.substring(1)));
            if (!ok) {
                violations.add("code de erro fora da tabela do contrato: " + code);
            }
        }
        System.out.println("QA-E5-PROP codes vistos: " + new TreeSet<>(world.seenCodes));
        assertThat(violations).as("violações").isEmpty();
    }

    private static String pick(Random rnd, List<String> list) {
        return list.get(rnd.nextInt(list.size()));
    }

    private String step(Actor a, Random rnd, List<String> people, Map<String, Integer> statuses, List<String> violations, String label) throws Exception {
        if (rnd.nextInt(100) < 28) {
            return goalStep(a, rnd, statuses, violations, label);
        }
        int roll = rnd.nextInt(100);
        Res r;
        String what;
        LocalDate today = today();
        String date = today.minusDays(rnd.nextInt(10)).toString();
        if (roll < 9) {
            long cents = 1 + rnd.nextInt(60_000);
            String exp = rnd.nextInt(3) == 0 ? null : today.plusDays(rnd.nextInt(60) - 15).toString();
            r = DebtQa.loan(a, CardQaWorld.money(cents), date, pick(rnd, people), exp);
            what = "empréstimo";
        } else if (roll < 22) {
            List<JsonNode> open = openDebts(a, "RECEIVABLE");
            if (open.isEmpty()) {
                return "sem dívida a receber";
            }
            JsonNode d = open.get(rnd.nextInt(open.size()));
            long out = CardQaWorld.cents(d.get("outstanding"));
            long cents = switch (rnd.nextInt(5)) {
                case 0 -> out;
                case 1 -> Math.max(1, out / 2);
                case 2 -> out + 1;
                case 3 -> Math.max(1, 1 + rnd.nextInt((int) Math.max(1, out)));
                default -> 1;
            };
            r = DebtQa.received(a, CardQaWorld.money(cents), date, d.get("id").asString());
            what = "recebimento";
        } else if (roll < 26) {
            r = DebtQa.borrowed(a, CardQaWorld.money(1 + rnd.nextInt(50_000)), date, pick(rnd, people));
            what = "captação";
        } else if (roll < 34) {
            List<JsonNode> open = openDebts(a, "PAYABLE");
            if (open.isEmpty()) {
                return "sem dívida a pagar";
            }
            JsonNode d = open.get(rnd.nextInt(open.size()));
            long out = CardQaWorld.cents(d.get("outstanding"));
            long cents = switch (rnd.nextInt(4)) {
                case 0 -> out;
                case 1 -> Math.max(1, out / 3);
                case 2 -> out + 1;
                default -> Math.max(1, 1 + rnd.nextInt((int) Math.max(1, out)));
            };
            r = DebtQa.payment(a, CardQaWorld.money(cents), date, d.get("id").asString());
            what = "pagamento";
        } else if (roll < 40) {
            boolean payable = rnd.nextBoolean();
            String category = payable && rnd.nextBoolean() ? a.food : null;
            String exp = rnd.nextInt(3) == 0 ? null : today.plusDays(rnd.nextInt(60) - 15).toString();
            r = DebtQa.plainDebt(a, pick(rnd, people), payable ? "PAYABLE" : "RECEIVABLE", CardQaWorld.money(1 + rnd.nextInt(40_000)), date, exp, category);
            what = "dívida sem movimentação";
        } else if (roll < 45) {
            List<JsonNode> all = DebtQa.debts(a);
            if (all.isEmpty()) {
                return "sem dívida";
            }
            String id = all.get(rnd.nextInt(all.size())).get("id").asString();
            r = rnd.nextInt(3) == 0 ? DebtQa.voidDebt(a, id) : DebtQa.cancelDebt(a, id);
            what = "perdão/estorno do registro";
        } else if (roll < 60) {
            int n = rnd.nextInt(5) == 0 ? 1 + rnd.nextInt(20) : 1 + rnd.nextInt(4);
            long total = Math.max(n + 1, 100 + rnd.nextInt(120_000));
            List<String> shares = new ArrayList<>();
            long left = total - 1 - rnd.nextInt((int) Math.min(total - n, 3));
            long used = 0;
            for (int i = 0; i < n; i++) {
                long remainingParts = n - i;
                long share = i == n - 1 ? left - used : Math.max(1, (left - used) / remainingParts - rnd.nextInt(2));
                share = Math.max(1, Math.min(share, left - used - (remainingParts - 1)));
                used += share;
                shares.add(DebtQa.shareJson(CardQaWorld.money(share), pick(rnd, people)));
            }
            r = DebtQa.sharedExpense(a, CardQaWorld.money(total), date, shares);
            what = "despesa dividida " + (n == 1 ? "1" : n <= 4 ? "2-4" : "5+") + " partes";
        } else if (roll < 66) {
            List<Map<String, Object>> expenses = jdbc.queryForList("select id::text as id, category_id::text as cat, amount_cents as c from transactions "
                    + "where user_id = ? and type = 'EXPENSE' and status = 'ACTIVE' and account_id is not null", a.id);
            if (expenses.isEmpty()) {
                return "sem despesa";
            }
            Map<String, Object> e = expenses.get(rnd.nextInt(expenses.size()));
            long bound = ((Number) e.get("c")).longValue();
            r = DebtQa.accountRefund(a, CardQaWorld.money(1 + rnd.nextInt((int) Math.max(1, bound))), today.toString(), (String) e.get("cat"), (String) e.get("id"));
            what = "reembolso de despesa";
        } else if (roll < 74) {
            List<String> all = jdbc.queryForList("select id::text from transactions where user_id = ?", String.class, a.id);
            if (all.isEmpty()) {
                return "sem movimentação";
            }
            r = a.voidTx(pick(rnd, all));
            what = "estorno";
        } else if (roll < 79) {
            int op = rnd.nextInt(3);
            if (op == 0) {
                r = a.purchase(CardQaWorld.money(1 + rnd.nextInt(90_000)), new int[] {1, 1, 2, 3, 6}[rnd.nextInt(5)], date, "Compra");
                what = "compra no cartão";
            } else if (op == 1) {
                List<JsonNode> invoices = a.invoices();
                if (invoices.isEmpty()) {
                    return "sem fatura";
                }
                JsonNode inv = invoices.get(rnd.nextInt(invoices.size()));
                long out = CardQaWorld.cents(inv.get("outstanding"));
                r = a.pay(UUID.fromString(inv.get("id").asString()), CardQaWorld.money(Math.max(1, out)), today.toString(), null);
                what = "pagamento de fatura";
            } else {
                r = a.changeDays(a.card, 1 + rnd.nextInt(31), 1 + rnd.nextInt(31));
                what = "mudança de dias";
            }
        } else if (roll < 86) {
            r = createRecurrence(a, rnd, today);
            what = "recorrência";
        } else if (roll < 91) {
            List<JsonNode> pend = DebtQa.pending(a, today.plusDays(90));
            if (pend.isEmpty()) {
                return "sem ocorrência";
            }
            JsonNode o = pend.get(rnd.nextInt(pend.size()));
            String amount = rnd.nextInt(3) == 0 ? CardQaWorld.money(1 + rnd.nextInt(30_000)) : null;
            r = DebtQa.realize(a, o.get("recurrenceId").asString(), o.get("date").asString(), amount);
            what = "realizar " + (o.get("cardId").isNull() ? "conta" : "cartão");
        } else if (roll < 94) {
            List<JsonNode> pend = DebtQa.pending(a, today.plusDays(90));
            if (pend.isEmpty()) {
                return "sem ocorrência";
            }
            JsonNode o = pend.get(rnd.nextInt(pend.size()));
            r = DebtQa.skip(a, o.get("recurrenceId").asString(), o.get("date").asString());
            what = "pular";
        } else if (roll < 95) {
            List<JsonNode> recs = DebtQa.all(a, "/v1/recurrences");
            if (recs.isEmpty()) {
                return "sem recorrência";
            }
            String id = recs.get(rnd.nextInt(recs.size())).get("id").asString();
            boolean off = rnd.nextBoolean();
            r = a.call(HttpMethod.POST, "/v1/recurrences/" + id + (off ? "/deactivate" : "/end"), off ? null : "{}", null);
            what = "encerrar/desativar";
        } else if (roll < 98) {
            int days = 1 + rnd.nextInt(20);
            a.at(clock.instant().plusSeconds(days * 86_400L));
            return "tempo +" + days + "d";
        } else {
            r = switch (rnd.nextInt(4)) {
                case 0 -> DebtQa.received(a, "10.00", date, UUID.randomUUID().toString());
                case 1 -> DebtQa.realize(a, UUID.randomUUID().toString(), date, null);
                case 2 -> DebtQa.cancelDebt(a, UUID.randomUUID().toString());
                default -> DebtQa.loan(a, "0.00", date, pick(rnd, people), null);
            };
            what = "inválida";
        }
        statuses.merge(what + ":" + r.status(), 1, Integer::sum);
        if (r.status() >= 500) {
            violations.add(label + ": " + what + " respondeu " + r);
        } else if (r.status() == 400) {
            violations.add(label + ": " + what + " com corpo válido deu 400 " + r);
        }
        return what;
    }

    private Res createRecurrence(Actor a, Random rnd, LocalDate today) throws Exception {
        String type = new String[] {"EXPENSE", "EXPENSE", "INCOME", "CARD_PURCHASE"}[rnd.nextInt(4)];
        String freq = new String[] {"DAILY", "WEEKLY", "MONTHLY", "YEARLY", "CUSTOM"}[rnd.nextInt(5)];
        LocalDate start = today.minusDays(rnd.nextInt(25)).plusDays(rnd.nextInt(3) == 0 ? 20 : 0);
        String target = "CARD_PURCHASE".equals(type) ? "\"cardId\":\"" + a.card + "\"" : DebtQa.accountTarget(a, type);
        String category = "INCOME".equals(type) ? a.income : "CARD_PURCHASE".equals(type) ? a.tech : (rnd.nextBoolean() ? a.food : a.tech);
        List<String> extra = new ArrayList<>();
        if ("CUSTOM".equals(freq)) {
            extra.add("\"interval\":" + (1 + rnd.nextInt(4)) + ",\"unit\":\"" + new String[] {"DAYS", "WEEKS", "MONTHS"}[rnd.nextInt(3)] + "\"");
        }
        if ("DAILY".equals(freq) || rnd.nextInt(3) == 0) {
            extra.add("\"maxOccurrences\":" + (2 + rnd.nextInt(8)));
        }
        return a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody(type, CardQaWorld.money(100 + rnd.nextInt(30_000)), "Rec " + rnd.nextInt(100),
                category, target, freq, start.toString(), extra.isEmpty() ? null : String.join(",", extra)), null);
    }

    private List<JsonNode> openDebts(Actor a, String direction) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode d : DebtQa.debts(a)) {
            String status = d.get("status").asString();
            if (direction.equals(d.get("direction").asString()) && ("OPEN".equals(status) || "PARTIALLY_SETTLED".equals(status))) {
                out.add(d);
            }
        }
        return out;
    }

    private final Set<String> monthlyNegatives = new TreeSet<>();

    private void verify(Actor a, List<String> v, String label) throws Exception {
        List<String> found = new ArrayList<>();
        for (Map<String, Object> m : jdbc.queryForList("""
                select p.id::text as id, sum(lp.amount_cents) as consumption from transactions p
                join ledger_postings lp on lp.active and lp.book_type = 'EXPENSE'
                  and (lp.transaction_id = p.id or lp.transaction_id in (select r.id from transactions r where r.refund_of_id = p.id and r.status = 'ACTIVE'))
                where p.user_id = ? and p.status = 'ACTIVE' and p.type in ('EXPENSE','CARD_PURCHASE') group by p.id having sum(lp.amount_cents) < 0""", a.id)) {
            found.add("consumo negativo na despesa/compra " + m.get("id") + ": " + m.get("consumption"));
        }
        if (a.ledgerSum() != 0) {
            found.add("razão não fecha em zero: " + a.ledgerSum());
        }
        if (a.unbalancedGroups() != 0) {
            found.add("grupos desbalanceados: " + a.unbalancedGroups());
        }
        found.addAll(CardInvariants.check(a, a.card));

        List<JsonNode> debts = DebtQa.debts(a);
        long recvOut = 0;
        long payOut = 0;
        Map<String, Long> netByPerson = new HashMap<>();
        for (JsonNode d : debts) {
            long principal = CardQaWorld.cents(d.get("principal"));
            long settled = CardQaWorld.cents(d.get("settled"));
            long outstanding = CardQaWorld.cents(d.get("outstanding"));
            String status = d.get("status").asString();
            String id = d.get("id").asString();
            if (outstanding < 0 || settled < 0) {
                found.add("dívida " + id + " com saldo negativo: settled " + settled + " outstanding " + outstanding);
            }
            if (settled > principal) {
                found.add("dívida " + id + " quitada acima do principal: " + settled + " > " + principal);
            }
            boolean open = "OPEN".equals(status) || "PARTIALLY_SETTLED".equals(status);
            if (open && settled + outstanding != principal) {
                found.add("dívida aberta " + id + ": settled + outstanding != principal");
            }
            if ("SETTLED".equals(status) && outstanding != 0) {
                found.add("dívida SETTLED " + id + " com saldo " + outstanding);
            }
            if (("CANCELED".equals(status) || "VOIDED".equals(status)) && outstanding != 0) {
                found.add("dívida " + status + " " + id + " com saldo " + outstanding);
            }
            long sumSettlements = 0;
            for (JsonNode s : d.get("settlements")) {
                if (s.get("active").asBoolean()) {
                    sumSettlements += CardQaWorld.cents(s.get("amount"));
                }
            }
            if (!"VOIDED".equals(status) && sumSettlements != settled && !"CANCELED".equals(status)) {
                found.add("dívida " + id + ": Σ settlements " + sumSettlements + " != settled " + settled);
            }
            if (open) {
                boolean recv = "RECEIVABLE".equals(d.get("direction").asString());
                if (recv) {
                    recvOut += outstanding;
                } else {
                    payOut += outstanding;
                }
                netByPerson.merge(d.get("person").get("id").asString(), recv ? outstanding : -outstanding, Long::sum);
            }
        }
        long book = bookSum(a, "RECEIVABLE");
        if (book != recvOut) {
            found.add("livro RECEIVABLE " + book + " != Σ saldo a receber " + recvOut);
        }
        long payBook = bookSum(a, "PAYABLE");
        if (payBook != -payOut) {
            found.add("livro PAYABLE " + payBook + " != −Σ saldo a pagar " + (-payOut));
        }

        for (JsonNode p : DebtQa.all(a, "/v1/people")) {
            String pid = p.get("id").asString();
            long net = CardQaWorld.cents(p.get("net"));
            long expected = netByPerson.getOrDefault(pid, 0L);
            if (net != expected) {
                found.add("pessoa " + p.get("name").asString() + " net " + net + " != Σ dívidas " + expected);
            }
            JsonNode detail = a.getOk("/v1/people/" + pid);
            if (CardQaWorld.cents(detail.get("net")) != net) {
                found.add("pessoa " + pid + ": net do detalhe != net da lista");
            }
            long detailSum = 0;
            for (JsonNode dd : detail.get("debts")) {
                String st = dd.get("status").asString();
                if ("OPEN".equals(st) || "PARTIALLY_SETTLED".equals(st)) {
                    long o = CardQaWorld.cents(dd.get("outstanding"));
                    detailSum += "RECEIVABLE".equals(dd.get("direction").asString()) ? o : -o;
                }
            }
            if (detailSum != net) {
                found.add("pessoa " + pid + ": Σ debts[] do detalhe " + detailSum + " != net " + net);
            }
        }

        Long leaked = jdbc.queryForObject("select count(*) from ledger_postings lp join transactions t on t.id = lp.transaction_id where lp.user_id = ? and lp.active "
                + "and lp.book_type in ('INCOME') and t.type in ('LOAN_GIVEN','MONEY_RECEIVED','DEBT_PAYMENT')", Long.class, a.id);
        Long leakedExpense = jdbc.queryForObject("select count(*) from ledger_postings lp join transactions t on t.id = lp.transaction_id where lp.user_id = ? and lp.active "
                + "and lp.book_type = 'EXPENSE' and t.type in ('LOAN_GIVEN','MONEY_RECEIVED','DEBT_PAYMENT')", Long.class, a.id);
        if (leaked != null && leaked > 0 || leakedExpense != null && leakedExpense > 0) {
            found.add("movimentação de dívida gerou lançamento de receita/despesa: INCOME " + leaked + " EXPENSE " + leakedExpense);
        }

        List<String> competences = jdbc.queryForList("select distinct competence from ledger_postings where user_id = ? and active and book_type = 'EXPENSE' order by 1",
                String.class, a.id);
        for (String c : competences) {
            long ledger = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = 'EXPENSE' and competence = ?",
                    Long.class, a.id, c);
            JsonNode entries = a.getOk("/v1/entries?competence=" + c + "&pageSize=50");
            long total = CardQaWorld.cents(entries.get("totals").get("total"));
            if (total != ledger) {
                found.add("entries " + c + " total " + total + " != razão EXPENSE " + ledger);
            }
            if (total < 0) {
                monthlyNegatives.add(c + ":" + total);
            }
            long byCategory = 0;
            for (JsonNode ca : entries.get("totals").get("byCategory")) {
                long amount = CardQaWorld.cents(ca.get("amount"));
                byCategory += amount;
                if (amount < 0) {
                    monthlyNegatives.add(c + "/" + ca.get("category").get("name").asString() + ":" + amount);
                }
            }
            if (byCategory != total) {
                found.add("entries " + c + ": Σ byCategory " + byCategory + " != total " + total);
            }
            if (!entries.get("hasNext").asBoolean()) {
                long rows = 0;
                for (JsonNode e : entries.get("items")) {
                    rows += CardQaWorld.cents(e.get("amount"));
                }
                if (entries.has("debtItems")) {
                    for (JsonNode e : entries.get("debtItems")) {
                        rows += CardQaWorld.cents(e.get("amount"));
                    }
                }
                if (rows != total) {
                    found.add("entries " + c + ": Σ linhas (itens + debtItems) " + rows + " != total " + total);
                }
            }
        }

        LocalDate today = today();
        JsonNode summary = a.getOk("/v1/dashboard/summary");
        JsonNode month = summary.get("month");
        String competence = month.get("competence").asString();
        long monthConsumption = CardQaWorld.cents(month.get("consumption"));
        long entriesTotal = CardQaWorld.cents(a.getOk("/v1/entries?competence=" + competence).get("totals").get("total"));
        if (monthConsumption != entriesTotal) {
            found.add("month.consumption " + monthConsumption + " != entries " + entriesTotal);
        }
        LocalDate first = LocalDate.parse(competence + "-01");
        JsonNode cashFlow = a.getOk("/v1/transactions?from=" + first + "&to=" + first.withDayOfMonth(first.lengthOfMonth())).get("totals").get("cashFlow");
        for (String f : List.of("in", "out", "net")) {
            if (CardQaWorld.cents(cashFlow.get(f)) != CardQaWorld.cents(month.get("cashFlow").get(f))) {
                found.add("lista.totals.cashFlow." + f + " " + cashFlow.get(f).get("amount") + " != month.cashFlow " + month.get("cashFlow").get(f).get("amount"));
            }
        }
        long income = CardQaWorld.cents(month.get("income"));
        long ledgerIncome = -jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = 'INCOME' and competence = ?",
                Long.class, a.id, competence);
        if (income != ledgerIncome) {
            found.add("month.income " + income + " != razão INCOME " + ledgerIncome);
        }
        long accountBalance = CardQaWorld.cents(a.getOk("/v1/accounts/" + a.nubank).get("currentBalance"));
        long ledgerBalance = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = 'ACCOUNT' "
                + "and book_id = ?::uuid and effective_date <= ?", Long.class, a.id, a.nubank, java.sql.Date.valueOf(today));
        if (accountBalance != ledgerBalance) {
            found.add("saldo da conta " + accountBalance + " != abertura + razão ACCOUNT " + ledgerBalance);
        }
        long inAccounts = CardQaWorld.cents(summary.get("balances").get("inAccounts"));
        if (inAccounts != accountBalance) {
            found.add("balances.inAccounts " + inAccounts + " != saldo da conta " + accountBalance);
        }
        JsonNode nw = summary.get("netWorth");
        if (CardQaWorld.cents(nw.get("receivables")) != recvOut) {
            found.add("netWorth.receivables " + nw.get("receivables").get("amount") + " != Σ saldo a receber " + recvOut);
        }
        if (CardQaWorld.cents(nw.get("payables")) != -payOut) {
            found.add("netWorth.payables " + nw.get("payables").get("amount") + " != Σ saldo a pagar " + payOut);
        }
        long expectedTotal = CardQaWorld.cents(nw.get("accounts")) + CardQaWorld.cents(nw.get("receivables")) + CardQaWorld.cents(nw.get("cardLiabilities"))
                + CardQaWorld.cents(nw.get("payables"));
        if (CardQaWorld.cents(nw.get("total")) != expectedTotal) {
            found.add("netWorth.total " + nw.get("total").get("amount") + " != contas + a receber − cartão − a pagar " + expectedTotal);
        }
        long equity = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type in ('ACCOUNT','RECEIVABLE','PAYABLE','CARD')",
                Long.class, a.id);
        long ledgerNetWorth = equity;
        long ledgerNetWorthToday = ledgerNetWorth;
        if (CardQaWorld.cents(nw.get("total")) != ledgerNetWorthToday) {
            long upToToday = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active "
                    + "and book_type in ('ACCOUNT','RECEIVABLE','PAYABLE','CARD') and effective_date <= ?", Long.class, a.id, java.sql.Date.valueOf(today));
            if (CardQaWorld.cents(nw.get("total")) != upToToday) {
                found.add("netWorth.total " + nw.get("total").get("amount") + " != Σ livros (abertura + ACCOUNT + RECEIVABLE + PAYABLE + CARD) " + upToToday);
            }
        }

        Long dup = jdbc.queryForObject("select count(*) from (select recurrence_id, occurrence_date from transactions where user_id = ? and status = 'ACTIVE' "
                + "and recurrence_id is not null group by 1, 2 having count(*) > 1) t", Long.class, a.id);
        if (dup != null && dup > 0) {
            found.add("ocorrência realizada duas vezes: " + dup);
        }
        Long skippedAndRealized = jdbc.queryForObject("select count(*) from recurrence_skips s join transactions t on t.recurrence_id = s.recurrence_id "
                + "and t.occurrence_date = s.occurrence_date and t.status = 'ACTIVE' where s.user_id = ?", Long.class, a.id);
        if (skippedAndRealized != null && skippedAndRealized > 0) {
            found.add("ocorrência pulada e realizada ao mesmo tempo: " + skippedAndRealized);
        }
        LocalDate to = today.plusDays(120);
        JsonNode upcoming = a.getOk("/v1/dashboard/upcoming?from=" + today + "&to=" + to);
        long outExpected = 0;
        long inExpected = 0;
        Map<String, Long> expectedByKey = new TreeMap<>();
        for (JsonNode inv : a.invoices()) {
            long o = CardQaWorld.cents(inv.get("outstanding"));
            if (!"PAID".equals(inv.get("status").asString()) && o > 0 && !LocalDate.parse(inv.get("dueDate").asString()).isAfter(to)) {
                outExpected += o;
                expectedByKey.merge("INVOICE|" + inv.get("dueDate").asString(), o, Long::sum);
            }
        }
        for (JsonNode o : DebtQa.pending(a, to)) {
            long amount = CardQaWorld.cents(o.get("amount"));
            if ("OUT".equals(o.get("direction").asString())) {
                outExpected += amount;
            } else {
                inExpected += amount;
            }
            expectedByKey.merge("RECURRENCE|" + o.get("recurrenceId").asString() + "|" + o.get("date").asString(), amount, Long::sum);
        }
        for (JsonNode d : debts) {
            String status = d.get("status").asString();
            if (("OPEN".equals(status) || "PARTIALLY_SETTLED".equals(status)) && !d.get("expectedDate").isNull()
                    && !LocalDate.parse(d.get("expectedDate").asString()).isAfter(to)) {
                long o = CardQaWorld.cents(d.get("outstanding"));
                if ("PAYABLE".equals(d.get("direction").asString())) {
                    outExpected += o;
                } else {
                    inExpected += o;
                }
                expectedByKey.merge("DEBT|" + d.get("id").asString(), o, Long::sum);
            }
        }
        for (Map<String, Object> row : jdbc.queryForList("select lp.transaction_id::text tx, lp.effective_date d, sum(lp.amount_cents) net from ledger_postings lp "
                + "join accounts ac on ac.id = lp.book_id where lp.user_id = ? and lp.active and lp.source_type = 'TRANSACTION' and lp.book_type = 'ACCOUNT' "
                + "and ac.type <> 'INVESTMENT' and lp.effective_date > ? and lp.effective_date <= ? group by lp.transaction_id, lp.effective_date having sum(lp.amount_cents) <> 0",
                a.id, java.sql.Date.valueOf(today), java.sql.Date.valueOf(to))) {
            long net = ((Number) row.get("net")).longValue();
            if (net < 0) {
                outExpected += -net;
            } else {
                inExpected += net;
            }
            expectedByKey.merge("SCHEDULED|" + row.get("tx") + "|" + row.get("d"), Math.abs(net), Long::sum);
        }
        long upOut = CardQaWorld.cents(upcoming.get("totals").get("out"));
        long upIn = CardQaWorld.cents(upcoming.get("totals").get("in"));
        if (upOut != outExpected) {
            found.add("Σ /upcoming OUT " + upOut + " != faturas + ocorrências + dívidas a pagar datadas " + outExpected);
        }
        if (upIn != inExpected) {
            found.add("Σ /upcoming IN " + upIn + " != ocorrências de receita + dívidas a receber datadas " + inExpected);
        }
        Map<String, Long> actualByKey = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        long sumItems = 0;
        for (JsonNode i : upcoming.get("items")) {
            String kind = i.get("kind").asString();
            String key = kind.startsWith("INVOICE") ? "INVOICE|" + i.get("dueDate").asString()
                    : "DEBT_PAYABLE".equals(kind) || "DEBT_RECEIVABLE".equals(kind) ? "DEBT|" + i.get("refId").asString()
                    : "SCHEDULED_TRANSACTION".equals(kind) ? "SCHEDULED|" + i.get("refId").asString() + "|" + i.get("dueDate").asString()
                    : "RECURRENCE|" + i.get("refId").asString() + "|" + i.get("dueDate").asString();
            long amount = CardQaWorld.cents(i.get("amount"));
            actualByKey.merge(key, amount, Long::sum);
            if (!seen.add(kind + "|" + i.get("refId").asString() + "|" + i.get("dueDate").asString())) {
                found.add("item duplicado em /upcoming: " + kind + " " + i.get("refId") + " " + i.get("dueDate"));
            }
            sumItems += "OUT".equals(i.get("direction").asString()) ? amount : 0;
        }
        if (!actualByKey.equals(expectedByKey)) {
            Map<String, Long> missing = new TreeMap<>(expectedByKey);
            actualByKey.forEach((k, val) -> missing.remove(k, val));
            Map<String, Long> extra = new TreeMap<>(actualByKey);
            expectedByKey.forEach((k, val) -> extra.remove(k, val));
            found.add("itens de /upcoming != fontes: faltam/diferem " + missing + " ; sobram/diferem " + extra);
        }
        if (sumItems != upOut) {
            found.add("Σ itens OUT " + sumItems + " != totals.out " + upOut);
        }
        Set<String> undatedExpected = new TreeSet<>();
        for (JsonNode d : debts) {
            String status = d.get("status").asString();
            if (("OPEN".equals(status) || "PARTIALLY_SETTLED".equals(status)) && d.get("expectedDate").isNull()) {
                undatedExpected.add(d.get("id").asString());
            }
        }
        Set<String> undatedActual = new TreeSet<>();
        for (JsonNode i : upcoming.get("undated")) {
            undatedActual.add(i.get("refId").asString());
        }
        if (!undatedActual.equals(undatedExpected)) {
            found.add("undated " + undatedActual + " != dívidas abertas sem data " + undatedExpected);
        }
        verifyGoals(a, found);
        verifyProjections(a, found);
        verifySummaries(a, found);
        found.forEach(f -> v.add(label + ": " + f));
    }

    private long bookSum(Actor a, String book) {
        Long s = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = ?", Long.class, a.id, book);
        return s == null ? 0 : s;
    }

    private static final class Ctx {
        String inv;
        String second;
        final List<String> goals = new ArrayList<>();
    }

    private Ctx ctx;

    private String currentMonth() {
        return today().toString().substring(0, 7);
    }

    private Map<String, String> snapshot(Actor a) throws Exception {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("transactions", String.valueOf(a.count("transactions")));
        m.put("postings", String.valueOf(a.count("ledger_postings")));
        m.put("ledger", String.valueOf(a.ledgerSum()));
        JsonNode s = a.getOk("/v1/dashboard/summary");
        m.put("month", s.get("month").toString());
        m.put("netWorth", s.get("netWorth").toString());
        m.put("inAccounts", s.get("balances").get("inAccounts").toString());
        m.put("investments", s.get("balances").get("investments").toString());
        m.put("committed", s.get("committed").toString());
        m.put("projected", s.get("projected").toString());
        for (String acc : List.of(a.nubank, ctx.inv, ctx.second)) {
            m.put("balance " + acc, a.getOk("/v1/accounts/" + acc).get("currentBalance").toString());
        }
        JsonNode p = a.getOk("/v1/projections?horizon=M12");
        m.put("projeção.série", p.get("series").toString());
        m.put("projeção.início", p.get("startingBalance").toString());
        m.put("resumo do mês", a.getOk("/v1/summaries/" + currentMonth()).toString());
        m.put("entries do mês", a.getOk("/v1/entries?competence=" + currentMonth() + "&pageSize=50").get("totals").toString());
        return m;
    }

    private static long balanceCents(Actor a, String accountId) throws Exception {
        return CardQaWorld.cents(a.getOk("/v1/accounts/" + accountId).get("currentBalance"));
    }

    private List<long[]> reservationsOf(Actor a, String goalId, String accountId) {
        List<long[]> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("select reserved_on d, amount_cents c from goal_reservations where user_id = ? and goal_id = ?::uuid "
                + "and account_id = ?::uuid", a.id, goalId, accountId)) {
            out.add(new long[] {((java.sql.Date) row.get("d")).toLocalDate().toEpochDay(), ((Number) row.get("c")).longValue()});
        }
        return out;
    }

    private static boolean neverNegative(List<long[]> items) {
        java.util.TreeMap<Long, Long> byDay = new java.util.TreeMap<>();
        for (long[] i : items) {
            byDay.merge(i[0], i[1], Long::sum);
        }
        long running = 0;
        for (long v : byDay.values()) {
            running += v;
            if (running < 0) {
                return false;
            }
        }
        return true;
    }

    private String goalStep(Actor a, Random rnd, Map<String, Integer> statuses, List<String> violations, String label) throws Exception {
        LocalDate today = today();
        Map<String, String> before = snapshot(a);
        int roll = rnd.nextInt(100);
        String what;
        Res r;
        if (roll < 9 && ctx.goals.size() < 7) {
            String deadline = rnd.nextInt(4) == 0 ? "" : ",\"deadline\":\"" + today.plusDays(rnd.nextInt(700) - 40) + "\"";
            r = a.call(HttpMethod.POST, "/v1/goals", "{\"name\":\"Meta " + rnd.nextInt(1000) + "\",\"target\":\"" + CardQaWorld.money(1 + rnd.nextInt(5_000_000)) + "\""
                    + deadline + ",\"priority\":\"" + new String[] {"HIGH", "MEDIUM", "LOW"}[rnd.nextInt(3)] + "\"}", rnd.nextBoolean() ? DebtQa.key() : null);
            if (r.status() == 201) {
                ctx.goals.add(r.node().get("id").asString());
            }
            what = "criar meta";
        } else if (roll < 16) {
            String id = pick(rnd, ctx.goals);
            boolean archived = jdbc.queryForObject("select archived from goals where id = ?::uuid", Boolean.class, id);
            r = a.call(HttpMethod.POST, "/v1/goals/" + id + (archived ? "/unarchive" : "/archive"), null, null);
            what = archived ? "desarquivar meta" : "arquivar meta";
        } else if (roll < 24) {
            String id = pick(rnd, ctx.goals);
            JsonNode g = a.getOk("/v1/goals/" + id);
            boolean stale = rnd.nextInt(5) == 0 && g.get("version").asLong() > 0;
            long version = g.get("version").asLong() - (stale ? 1 : 0);
            String deadline = rnd.nextInt(3) == 0 ? "" : ",\"deadline\":\"" + today.plusDays(rnd.nextInt(700) - 40) + "\"";
            r = a.call(HttpMethod.PUT, "/v1/goals/" + id, "{\"name\":\"Meta ed " + rnd.nextInt(1000) + "\",\"target\":\"" + CardQaWorld.money(1 + rnd.nextInt(5_000_000))
                    + "\"" + deadline + ",\"priority\":\"LOW\",\"version\":" + version + "}", null);
            boolean archived = jdbc.queryForObject("select archived from goals where id = ?::uuid", Boolean.class, id);
            int expected = stale ? 409 : archived ? 422 : 200;
            if (r.status() != expected) {
                violations.add(label + ": PUT da meta (arquivada=" + archived + ", velha=" + stale + ") deu " + r + " esperado " + expected);
            }
            what = "editar meta";
        } else if (roll < 30) {
            JsonNode acc = a.getOk("/v1/accounts/" + ctx.second);
            boolean archived = "ARCHIVED".equals(acc.get("status").asString());
            boolean hasReserve = Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from (select gr.goal_id from goal_reservations gr join goals g on g.id = gr.goal_id "
                    + "where gr.account_id = ?::uuid and not g.archived group by gr.goal_id having sum(gr.amount_cents) > 0) t)", Boolean.class, ctx.second));
            r = a.call(HttpMethod.POST, "/v1/accounts/" + ctx.second + (archived ? "/unarchive" : "/archive"), null, null);
            what = archived ? "desarquivar conta" : "arquivar conta";
            if (!archived && hasReserve && r.status() == 200) {
                violations.add(label + ": conta arquivada com reserva ativa positiva (S-259): " + r);
            }
            if (!archived && hasReserve && r.status() == 422 && !"ACCOUNT_HAS_RESERVATIONS".equals(r.code()) && !r.code().startsWith("ACCOUNT_WITH_")) {
                violations.add(label + ": arquivar conta com reserva deu " + r + " (esperado ACCOUNT_HAS_RESERVATIONS ou erro de saldo)");
            }
            if (!archived && !hasReserve && r.status() == 422 && "ACCOUNT_HAS_RESERVATIONS".equals(r.code())) {
                violations.add(label + ": ACCOUNT_HAS_RESERVATIONS sem reserva ativa: " + r);
            }
        } else {
            String goalId = pick(rnd, ctx.goals);
            String acc = pick(rnd, List.of(a.nubank, a.nubank, ctx.inv, ctx.second));
            boolean release = rnd.nextInt(100) < 38;
            List<long[]> existing = reservationsOf(a, goalId, acc);
            long net = existing.stream().mapToLong(i -> i[1]).sum();
            LocalDate date = today.plusDays(rnd.nextInt(100) < 22 ? rnd.nextInt(60) : rnd.nextInt(8) - 5);
            long cents;
            if (release) {
                long ceiling = Math.max(1, Math.min(net + 50_000, 3_000_000));
                cents = -(net > 0 && rnd.nextInt(3) > 0 ? 1 + (long) rnd.nextInt((int) Math.min(net, 3_000_000)) : 1 + (long) rnd.nextInt((int) ceiling));
            } else {
                long balance = balanceCents(a, acc);
                cents = switch (rnd.nextInt(4)) {
                    case 0 -> balance > 0 ? balance : 1_000;
                    case 1 -> 1 + rnd.nextInt(5_000_000);
                    case 2 -> 1 + rnd.nextInt(10_000);
                    default -> 1 + rnd.nextInt(800_000);
                };
            }
            boolean goalArchived = jdbc.queryForObject("select archived from goals where id = ?::uuid", Boolean.class, goalId);
            boolean accountArchived = "ARCHIVED".equals(a.getOk("/v1/accounts/" + acc).get("status").asString());
            String expectedCode;
            if (cents > 0 && accountArchived) {
                expectedCode = "ACCOUNT_ARCHIVED";
            } else if (goalArchived) {
                expectedCode = "GOAL_ARCHIVED";
            } else {
                List<long[]> all = new ArrayList<>(existing);
                all.add(new long[] {date.toEpochDay(), cents});
                expectedCode = neverNegative(all) ? null : "GOAL_RELEASE_EXCEEDS_RESERVED";
            }
            String key = DebtQa.key();
            String body = "{\"accountId\":\"" + acc + "\",\"amount\":\"" + CardQaWorld.money(cents) + "\",\"date\":\"" + date + "\"}";
            long countBefore = jdbc.queryForObject("select count(*) from goal_reservations where user_id = ?", Long.class, a.id);
            r = a.call(HttpMethod.POST, "/v1/goals/" + goalId + "/reservations", body, key);
            if (expectedCode == null) {
                if (r.status() != 200) {
                    violations.add(label + ": reserva/liberação válida recusada " + r + " corpo " + body);
                }
            } else if (r.status() != 422 || !expectedCode.equals(r.code())) {
                violations.add(label + ": esperava 422 " + expectedCode + " e veio " + r + " (corpo " + body + ")");
            }
            long countAfter = jdbc.queryForObject("select count(*) from goal_reservations where user_id = ?", Long.class, a.id);
            if (countAfter != countBefore + (r.status() == 200 ? 1 : 0)) {
                violations.add(label + ": linhas de reserva " + countBefore + " -> " + countAfter + " com status " + r.status());
            }
            if (r.status() == 200 && rnd.nextInt(3) == 0) {
                Res replay = a.call(HttpMethod.POST, "/v1/goals/" + goalId + "/reservations", body, key);
                long countReplay = jdbc.queryForObject("select count(*) from goal_reservations where user_id = ?", Long.class, a.id);
                if (replay.status() != 200 || countReplay != countAfter) {
                    violations.add(label + ": replay idempotente " + replay + " linhas " + countAfter + " -> " + countReplay);
                }
            }
            what = release ? "liberar" : "reservar";
        }
        Map<String, String> after = snapshot(a);
        for (Map.Entry<String, String> e : before.entrySet()) {
            if (!e.getValue().equals(after.get(e.getKey()))) {
                violations.add(label + ": " + what + " mudou '" + e.getKey() + "': " + e.getValue() + " -> " + after.get(e.getKey()));
            }
        }
        statuses.merge(what + ":" + r.status(), 1, Integer::sum);
        if (r.status() >= 500 || r.status() == 400) {
            violations.add(label + ": " + what + " respondeu " + r);
        }
        return what;
    }

    private static String ratio(long numerator, long denominator) {
        return java.math.BigDecimal.valueOf(numerator).divide(java.math.BigDecimal.valueOf(denominator), 4, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private void verifyGoals(Actor a, List<String> found) throws Exception {
        LocalDate today = today();
        List<String> accounts = List.of(a.nubank, ctx.inv, ctx.second);
        Map<String, long[]> goalRows = new HashMap<>();
        Map<String, LocalDate> deadlines = new HashMap<>();
        for (Map<String, Object> g : jdbc.queryForList("select id::text id, target_cents t, archived ar, deadline dl from goals where user_id = ?", a.id)) {
            goalRows.put((String) g.get("id"), new long[] {((Number) g.get("t")).longValue(), (Boolean) g.get("ar") ? 1 : 0});
            deadlines.put((String) g.get("id"), g.get("dl") == null ? null : ((java.sql.Date) g.get("dl")).toLocalDate());
        }
        Map<String, Map<String, Long>> net = new HashMap<>();
        Map<String, Long> reservedByAccount = new HashMap<>();
        Map<String, Integer> reservationCount = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("select goal_id::text g, account_id::text a, amount_cents c, reserved_on d from goal_reservations where user_id = ?", a.id)) {
            String g = (String) r.get("g");
            reservationCount.merge(g, 1, Integer::sum);
            LocalDate d = ((java.sql.Date) r.get("d")).toLocalDate();
            if (!d.isAfter(today)) {
                long c = ((Number) r.get("c")).longValue();
                net.computeIfAbsent(g, k -> new HashMap<>()).merge((String) r.get("a"), c, Long::sum);
                if (goalRows.get(g)[1] == 0) {
                    reservedByAccount.merge((String) r.get("a"), c, Long::sum);
                }
            }
        }
        Map<String, Long> balance = new HashMap<>();
        for (String acc : accounts) {
            long expected = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = 'ACCOUNT' "
                    + "and book_id = ?::uuid and effective_date <= ?", Long.class, a.id, acc, java.sql.Date.valueOf(today));
            balance.put(acc, expected);
        }
        long reservedInCash = 0;
        for (String acc : accounts) {
            JsonNode account = a.getOk("/v1/accounts/" + acc);
            long reserved = reservedByAccount.getOrDefault(acc, 0L);
            if (CardQaWorld.cents(account.get("currentBalance")) != balance.get(acc)) {
                found.add("conta " + acc + " currentBalance " + account.get("currentBalance").get("amount") + " != razão " + balance.get(acc));
            }
            if (!account.has("reservedAmount") || CardQaWorld.cents(account.get("reservedAmount")) != reserved) {
                found.add("conta " + acc + " reservedAmount " + account.path("reservedAmount") + " != Σ reservas (metas ativas, até hoje) " + reserved
                        + " (status " + account.get("status").asString() + ")");
            }
            boolean uncovered = reserved > 0 && reserved > balance.get(acc);
            if (!account.has("uncovered") || account.get("uncovered").asBoolean() != uncovered) {
                found.add("conta " + acc + " uncovered " + account.path("uncovered") + " != " + uncovered + " (reservado " + reserved + ", saldo " + balance.get(acc) + ")");
            }
            if (!acc.equals(ctx.inv)) {
                reservedInCash += reserved;
            }
        }
        for (JsonNode item : DebtQa.all(a, "/v1/accounts")) {
            String id = item.get("id").asString();
            if (accounts.contains(id) && !item.equals(a.getOk("/v1/accounts/" + id))) {
                found.add("conta " + id + ": item da lista != detalhe");
            }
        }
        JsonNode summary = a.getOk("/v1/dashboard/summary");
        JsonNode balances = summary.get("balances");
        if (CardQaWorld.cents(balances.get("reserved")) != reservedInCash) {
            found.add("balances.reserved " + balances.get("reserved").get("amount") + " != Σ reservedAmount das contas de caixa " + reservedInCash);
        }
        long expectedAvailable = CardQaWorld.cents(balances.get("inAccounts")) - CardQaWorld.cents(summary.get("committed").get("d30")) - reservedInCash;
        if (CardQaWorld.cents(balances.get("available")) != expectedAvailable) {
            found.add("balances.available " + balances.get("available").get("amount") + " != em contas − comprometido 30d − reservado " + expectedAvailable);
        }
        Set<String> seenInLists = new HashSet<>();
        for (String status : List.of("ACTIVE", "ACHIEVED", "ARCHIVED")) {
            for (JsonNode item : DebtQa.all(a, "/v1/goals?status=" + status)) {
                if (!status.equals(item.get("status").asString())) {
                    found.add("lista de metas " + status + " traz " + item.get("status").asString());
                }
                if (!seenInLists.add(item.get("id").asString())) {
                    found.add("meta em duas listas de status: " + item.get("id").asString());
                }
            }
        }
        List<JsonNode> allGoals = DebtQa.all(a, "/v1/goals");
        if (allGoals.size() != goalRows.size() || seenInLists.size() != goalRows.size()) {
            found.add("metas: lista " + allGoals.size() + ", por status " + seenInLists.size() + ", banco " + goalRows.size());
        }
        for (Map.Entry<String, long[]> e : goalRows.entrySet()) {
            String id = e.getKey();
            long target = e.getValue()[0];
            boolean archived = e.getValue()[1] == 1;
            long current = net.getOrDefault(id, Map.of()).values().stream().mapToLong(Long::longValue).sum();
            JsonNode g = a.getOk("/v1/goals/" + id);
            if (CardQaWorld.cents(g.get("current")) != current) {
                found.add("meta " + id + " current " + g.get("current").get("amount") + " != Σ reservas " + current);
            }
            if (CardQaWorld.cents(g.get("target")) != target) {
                found.add("meta " + id + " target diferente do banco");
            }
            if (!g.get("progress").asString().equals(ratio(current, target))) {
                found.add("meta " + id + " progress " + g.get("progress").asString() + " != " + ratio(current, target));
            }
            if (CardQaWorld.cents(g.get("remaining")) != Math.max(0, target - current)) {
                found.add("meta " + id + " remaining " + g.get("remaining").get("amount") + " != " + Math.max(0, target - current));
            }
            String status = archived ? "ARCHIVED" : current >= target ? "ACHIEVED" : "ACTIVE";
            if (!status.equals(g.get("status").asString())) {
                found.add("meta " + id + " status " + g.get("status").asString() + " != " + status);
            }
            LocalDate deadline = deadlines.get(id);
            Long monthly = null;
            if (current >= target) {
                monthly = 0L;
            } else if (deadline != null && !deadline.isBefore(today)) {
                long months = (deadline.getYear() - today.getYear()) * 12L + deadline.getMonthValue() - today.getMonthValue() + 1;
                monthly = Math.ceilDiv(target - current, months);
            }
            if (monthly == null ? !g.get("monthlyNeeded").isNull() : g.get("monthlyNeeded").isNull() || CardQaWorld.cents(g.get("monthlyNeeded")) != monthly) {
                found.add("meta " + id + " monthlyNeeded " + g.get("monthlyNeeded") + " != " + monthly + " (alvo " + target + ", atual " + current + ", prazo " + deadline + ", hoje " + today + ")");
            }
            boolean uncovered = false;
            if (!archived) {
                for (Map.Entry<String, Long> acc : net.getOrDefault(id, Map.of()).entrySet()) {
                    if (acc.getValue() > 0 && reservedByAccount.getOrDefault(acc.getKey(), 0L) > balance.get(acc.getKey())) {
                        uncovered = true;
                    }
                }
            }
            if (g.get("uncovered").asBoolean() != uncovered) {
                found.add("meta " + id + " uncovered " + g.get("uncovered").asBoolean() + " != " + uncovered);
            }
            if (g.get("reservations").size() != reservationCount.getOrDefault(id, 0)) {
                found.add("meta " + id + " reservations[] " + g.get("reservations").size() + " != banco " + reservationCount.getOrDefault(id, 0));
            }
            for (String acc : accounts) {
                if (!neverNegative(reservationsOf(a, id, acc))) {
                    found.add("meta " + id + " conta " + acc + ": saldo da reserva negativo em alguma data");
                }
            }
            JsonNode inList = allGoals.stream().filter(x -> x.get("id").asString().equals(id)).findFirst().orElse(null);
            if (inList == null || !inList.equals(g)) {
                found.add("meta " + id + ": item da lista != detalhe");
            }
        }
    }

    private void verifyProjections(Actor a, List<String> found) throws Exception {
        LocalDate today = today();
        JsonNode summary = a.getOk("/v1/dashboard/summary");
        long inAccounts = CardQaWorld.cents(summary.get("balances").get("inAccounts"));
        Map<String, LocalDate> ends = Map.of("D30", today.plusDays(30), "D90", today.plusDays(90), "M6", today.plusMonths(6), "M12", today.plusMonths(12));
        for (Map.Entry<String, LocalDate> h : ends.entrySet()) {
            JsonNode p = a.getOk("/v1/projections?horizon=" + h.getKey());
            String tag = "projeção " + h.getKey();
            if (!p.get("isProjection").asBoolean() || !p.get("asOf").asString().equals(today.toString())) {
                found.add(tag + ": isProjection/asOf errados");
            }
            long running = CardQaWorld.cents(p.get("startingBalance"));
            if (running != inAccounts) {
                found.add(tag + ": startingBalance " + running + " != balances.inAccounts " + inAccounts);
            }
            JsonNode series = p.get("series");
            String competence = today.toString().substring(0, 7);
            for (int i = 0; i < series.size(); i++) {
                JsonNode pt = series.get(i);
                if (!pt.get("competence").asString().equals(java.time.YearMonth.parse(competence).plusMonths(i).toString())) {
                    found.add(tag + ": ponto " + i + " competência " + pt.get("competence").asString());
                }
                running += CardQaWorld.cents(pt.get("inflows")) - CardQaWorld.cents(pt.get("outflows"));
                if (CardQaWorld.cents(pt.get("balance")) != running) {
                    found.add(tag + ": ponto " + i + " balance " + pt.get("balance").get("amount") + " != acumulado " + running);
                }
            }
            if (!series.get(series.size() - 1).get("periodEnd").asString().equals(h.getValue().toString())) {
                found.add(tag + ": último periodEnd " + series.get(series.size() - 1).get("periodEnd").asString() + " != " + h.getValue());
            }
            String projected = summary.get("projected").get(h.getKey().toLowerCase()).get("amount").asString();
            String last = series.get(series.size() - 1).get("balance").get("amount").asString();
            if (!projected.equals(last)) {
                found.add(tag + ": saldo final " + last + " != projected do dashboard " + projected);
            }
            JsonNode up = a.getOk("/v1/dashboard/upcoming?from=" + today + "&to=" + h.getValue());
            long viaUpcoming = inAccounts + CardQaWorld.cents(up.get("totals").get("in")) - CardQaWorld.cents(up.get("totals").get("out"));
            if (viaUpcoming != CardQaWorld.cents(series.get(series.size() - 1).get("balance"))) {
                found.add(tag + ": saldo final " + last + " != em contas + /upcoming IN − OUT " + viaUpcoming + " (dupla contagem ou item perdido)");
            }
            JsonNode t = a.getOk("/v1/projections?horizon=" + h.getKey() + "&includeTrend=true");
            if (!t.get("series").equals(series) || !t.get("startingBalance").equals(p.get("startingBalance")) || !t.get("goals").equals(p.get("goals"))) {
                found.add(tag + ": includeTrend=true mudou a série base (series=" + t.get("series").equals(series) + ", startingBalance=" + t.get("startingBalance").equals(p.get("startingBalance")) + ", goals=" + t.get("goals").equals(p.get("goals")) + ")\n" + p.get("goals") + "\n" + t.get("goals"));
            }
            JsonNode trend = t.get("trend");
            if (trend == null || !trend.get("estimate").asBoolean()) {
                found.add(tag + ": trend ausente ou sem estimate=true");
            } else if (trend.get("available").asBoolean()) {
                long avg = CardQaWorld.cents(trend.get("monthlyAverage"));
                if (avg < 0 || trend.get("series").size() != series.size()) {
                    found.add(tag + ": tendência com média negativa ou série de tamanho diferente");
                }
                for (int i = 0; i < trend.get("series").size() && i < series.size(); i++) {
                    long spent = CardQaWorld.cents(trend.get("series").get(i).get("estimatedSpending"));
                    long tb = CardQaWorld.cents(trend.get("series").get(i).get("balance"));
                    if (spent != avg * i || tb != CardQaWorld.cents(series.get(i).get("balance")) - spent) {
                        found.add(tag + ": ponto " + i + " da tendência incoerente (gasto " + spent + ", média " + avg + ")");
                    }
                }
            } else if (!"INSUFFICIENT_HISTORY".equals(trend.get("reason").asString())) {
                found.add(tag + ": tendência indisponível com motivo " + trend.get("reason"));
            }
        }
        JsonNode d30 = a.getOk("/v1/projections?horizon=D30");
        if (!d30.get("series").get(d30.get("series").size() - 1).get("balance").get("amount").asString()
                .equals(summary.get("projected").get("d30").get("amount").asString())) {
            found.add("D30 != projected.d30");
        }
        JsonNode m6 = a.getOk("/v1/projections?horizon=M6");
        if (!m6.get("goals").get("reserved").equals(summary.get("balances").get("reserved"))) {
            found.add("projeção.goals.reserved " + m6.get("goals").get("reserved") + " != balances.reserved " + summary.get("balances").get("reserved"));
        }
    }

    private void verifySummaries(Actor a, List<String> found) throws Exception {
        LocalDate today = today();
        java.time.YearMonth current = java.time.YearMonth.from(today);
        JsonNode dashboard = a.getOk("/v1/dashboard/summary").get("month");
        Map<String, JsonNode> byMonth = new TreeMap<>();
        for (int d = -3; d <= 2; d++) {
            byMonth.put(current.plusMonths(d).toString(), a.getOk("/v1/summaries/" + current.plusMonths(d)));
        }
        for (Map.Entry<String, JsonNode> e : byMonth.entrySet()) {
            String month = e.getKey();
            JsonNode s = e.getValue();
            String tag = "resumo " + month;
            long ledgerIncome = -jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = 'INCOME' and competence = ?",
                    Long.class, a.id, month);
            long ledgerExpense = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = 'EXPENSE' and competence = ?",
                    Long.class, a.id, month);
            long income = CardQaWorld.cents(s.get("income"));
            long expenses = CardQaWorld.cents(s.get("expenses"));
            JsonNode entries = a.getOk("/v1/entries?competence=" + month + "&pageSize=50");
            if (income != ledgerIncome) {
                found.add(tag + ": income " + income + " != razão INCOME " + ledgerIncome);
            }
            if (expenses != ledgerExpense) {
                found.add(tag + ": expenses " + expenses + " != razão EXPENSE " + ledgerExpense);
            }
            if (expenses != CardQaWorld.cents(entries.get("totals").get("total"))) {
                found.add(tag + ": expenses " + expenses + " != entries.totals.total " + entries.get("totals").get("total").get("amount"));
            }
            if (CardQaWorld.cents(s.get("net")) != income - expenses) {
                found.add(tag + ": net != receitas − consumo");
            }
            String rate = income == 0 ? null : ratio(income - expenses, income);
            if (rate == null ? !s.get("savingsRate").isNull() : !rate.equals(s.get("savingsRate").asString())) {
                found.add(tag + ": savingsRate " + s.get("savingsRate") + " != " + rate);
            }
            boolean future = YearMonth_isAfter(month, current);
            if (s.get("partial").asBoolean() != !java.time.YearMonth.parse(month).isBefore(current) || s.get("isProjection").asBoolean() != future) {
                found.add(tag + ": partial/isProjection errados");
            }
            if (month.equals(current.toString())) {
                if (income != CardQaWorld.cents(dashboard.get("income")) || expenses != CardQaWorld.cents(dashboard.get("consumption"))
                        || CardQaWorld.cents(s.get("net")) != CardQaWorld.cents(dashboard.get("net"))
                        || CardQaWorld.cents(s.get("investments")) != CardQaWorld.cents(dashboard.get("investedNet"))
                        || CardQaWorld.cents(s.get("card").get("consumption")) != CardQaWorld.cents(dashboard.get("credit"))) {
                    found.add(tag + ": resumo != dashboard.month (receita/consumo/net/investimento/crédito)");
                }
            }
            long bestCategory = 0;
            for (JsonNode c : entries.get("totals").get("byCategory")) {
                bestCategory = Math.max(bestCategory, CardQaWorld.cents(c.get("amount")));
            }
            JsonNode lc = s.get("largestCategory");
            if (bestCategory > 0 ? lc.isNull() || CardQaWorld.cents(lc.get("amount")) != bestCategory : !lc.isNull()) {
                found.add(tag + ": largestCategory " + lc + " != maior consumo positivo por categoria " + bestCategory);
            }
            long bestLine = 0;
            for (JsonNode i : entries.get("items")) {
                bestLine = Math.max(bestLine, CardQaWorld.cents(i.get("amount")));
            }
            if (entries.has("debtItems")) {
                for (JsonNode i : entries.get("debtItems")) {
                    bestLine = Math.max(bestLine, CardQaWorld.cents(i.get("amount")));
                }
            }
            if (!entries.get("hasNext").asBoolean()) {
                JsonNode le = s.get("largestExpense");
                if (bestLine > 0 ? le.isNull() || CardQaWorld.cents(le.get("amount")) != bestLine : !le.isNull()) {
                    found.add(tag + ": largestExpense " + le + " != maior linha de consumo " + bestLine);
                }
            }
            long future2 = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = 'EXPENSE' "
                    + "and installment_id is not null and competence > ?", Long.class, a.id, month);
            if (CardQaWorld.cents(s.get("futureInstallments")) != future2) {
                found.add(tag + ": futureInstallments " + s.get("futureInstallments").get("amount") + " != Σ parcelas depois do mês " + future2);
            }
            String previous = java.time.YearMonth.parse(month).minusMonths(1).toString();
            JsonNode cmp = s.get("comparison");
            JsonNode prev = byMonth.get(previous);
            if (!cmp.get("previousMonth").get("competence").asString().equals(previous)) {
                found.add(tag + ": previousMonth " + cmp.get("previousMonth").get("competence"));
            }
            if (prev != null) {
                for (String f : List.of("income", "expenses", "investments", "net")) {
                    if (!cmp.get("previousMonth").get(f).equals(prev.get(f))) {
                        found.add(tag + ": comparison.previousMonth." + f + " " + cmp.get("previousMonth").get(f).get("amount") + " != resumo de " + previous + " " + prev.get(f).get("amount"));
                    }
                }
            }
            for (String f : List.of("income", "expenses", "investments", "net")) {
                long cur = CardQaWorld.cents(s.get(f));
                long pr = CardQaWorld.cents(cmp.get("previousMonth").get(f));
                JsonNode delta = cmp.get(f + "Delta");
                if (CardQaWorld.cents(delta.get("amount")) != cur - pr) {
                    found.add(tag + ": " + f + "Delta.amount " + delta.get("amount").get("amount") + " != " + (cur - pr));
                }
                String pct = pr == 0 ? null : ratio(cur - pr, Math.abs(pr));
                if (pct == null ? !delta.get("percent").isNull() : !pct.equals(delta.get("percent").asString())) {
                    found.add(tag + ": " + f + "Delta.percent " + delta.get("percent") + " != " + pct);
                }
            }
        }
    }

    private static boolean YearMonth_isAfter(String month, java.time.YearMonth current) {
        return java.time.YearMonth.parse(month).isAfter(current);
    }
}
