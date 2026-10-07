package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
 * T113 (QA), critério 4: round-trip exportar -> importar com 1.000 notas aleatórias (semente fixa) e com casos de borda do frontmatter. O corpo
 * volta byte a byte; tags, título, wikilinks e vínculos explícitos voltam iguais; exportar de novo gera o mesmo zip.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T113: round-trip exportar -> importar (propriedade, semente fixa)")
class VaultRoundTripQaIT {

    private static final long SEED = 20261009L;

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
    private Actor a;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        w = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = w.new Actor("e6rt");
    }

    private void purge(Actor x) {
        new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(s -> jdbc.update("delete from notes where user_id = ?", x.id));
    }

    // ------------------------------------------------------------------------------------------------ gerador

    private static final String[] WORDS = {"Revisão", "orçamento", "MacBook", "café", "Ação", "naïve", "São Paulo", "日本語", "🚀 foguete", "Ünïcödé", "ÇÃO", "plano (B)",
            "a&b", "100%", "x_y", "o'brien", "@casa", "{chaves}", "~til", "uma frase com espaços", "UPPER", "lower", "Mixed Case", "ß", "İstanbul", "ǅ", "emoji 👨‍👩‍👧"};
    private static final String[] FOLDERS = {"", "", "", "projetos/", "reviews/", "diário/", "a/b/", "Pasta Com Espaço/", "inbox/ideias/", "ações/2026/"};

    private static String pick(Random r, String[] pool) {
        return pool[r.nextInt(pool.length)];
    }

    private record Gen(String path, String text) {
    }

    private List<Gen> generate(int n, Random r) {
        List<String> paths = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 0; paths.size() < n; i++) {
            String name = pick(r, WORDS) + (r.nextInt(3) == 0 ? "" : " " + i);
            String path = pick(r, FOLDERS) + name + ".md";
            if (keys.add(path.toLowerCase(java.util.Locale.ROOT)) && com.moneylog.core.note.domain.NotePath.isValid(path)) {
                paths.add(path);
            }
        }
        List<Gen> out = new ArrayList<>();
        for (String path : paths) {
            out.add(new Gen(path, text(r, paths)));
        }
        return out;
    }

    private String text(Random r, List<String> paths) {
        String nl = r.nextInt(4) == 0 ? "\r\n" : "\n";
        StringBuilder sb = new StringBuilder();
        int fm = r.nextInt(10);
        if (fm < 3) {
            sb.append("---").append(nl);
            sb.append("tags: [").append(pick(r, new String[] {"revisao", "Revisão Mensal", "ideia", "x-y"})).append(", ").append("t").append(r.nextInt(5)).append("]").append(nl);
            if (r.nextBoolean()) {
                sb.append("title: ").append(r.nextBoolean() ? "\"Título #" + r.nextInt(99) + "\"" : "Titulo " + r.nextInt(99)).append(nl);
            }
            if (r.nextBoolean()) {
                sb.append("aliases: [Apelido ").append(r.nextInt(9)).append("]").append(nl);
            }
            if (r.nextInt(3) == 0) {
                sb.append("cssclasses:").append(nl).append("  - wide").append(nl).append("  - ").append(pick(r, new String[] {"a", "b"})).append(nl);
            }
            if (r.nextInt(4) == 0) {
                sb.append("# comentário yaml").append(nl);
            }
            if (r.nextInt(5) == 0) {
                sb.append("nested:").append(nl).append("  k: v").append(nl).append("  lista:").append(nl).append("    - 1").append(nl);
            }
            sb.append(r.nextInt(6) == 0 ? "..." : "---").append(nl);
        }
        int lines = r.nextInt(12);
        for (int i = 0; i < lines; i++) {
            switch (r.nextInt(14)) {
                case 0 -> sb.append("# ").append(pick(r, WORDS));
                case 1 -> sb.append("- [ ] ").append(pick(r, WORDS)).append(" #").append(pick(r, new String[] {"tag", "revisao-mensal", "Ação", "x_y"}));
                case 2 -> sb.append(link(r, paths));
                case 3 -> sb.append("texto ").append(link(r, paths)).append(" e ").append(link(r, paths)).append(" fim");
                case 4 -> sb.append("`").append(link(r, paths)).append("` em código inline");
                case 5 -> sb.append("```").append(nl).append(link(r, paths)).append(nl).append("```");
                case 6 -> sb.append("~~~js").append(nl).append("const a = [[1,2],[3]]; ").append(link(r, paths)).append(nl).append("~~~");
                case 7 -> sb.append("| a | b |").append(nl).append("|---|---|").append(nl).append("| ").append(link(r, paths).replace("|", "\\|")).append(" | x |");
                case 8 -> sb.append("<script>alert(1)</script> <img src=x onerror=alert(2)> ").append(pick(r, WORDS));
                case 9 -> sb.append("   espaços no fim   ").append("\t").append("tab");
                case 10 -> sb.append("---");
                case 11 -> sb.append("$$ x^2 $$ ").append(pick(r, WORDS)).append(" ![[").append(pick(r, new String[] {"foto.png", "outra"})).append("]]");
                case 12 -> sb.append("");
                default -> sb.append(pick(r, WORDS)).append(' ').append(pick(r, WORDS));
            }
            sb.append(nl);
        }
        String text = sb.toString();
        if (r.nextInt(3) == 0 && text.endsWith(nl)) {
            text = text.substring(0, text.length() - nl.length());
        }
        return text;
    }

    private String link(Random r, List<String> paths) {
        String target = paths.get(r.nextInt(paths.size()));
        String noExt = target.substring(0, target.length() - 3);
        String name = noExt.substring(noExt.lastIndexOf('/') + 1);
        String base = switch (r.nextInt(5)) {
            case 0 -> noExt;
            case 1 -> name.toUpperCase(java.util.Locale.ROOT);
            case 2 -> name + ".md";
            case 3 -> "inexistente " + r.nextInt(20);
            default -> name;
        };
        if (base.contains("[") || base.contains("]")) {
            base = "x";
        }
        String section = r.nextInt(5) == 0 ? "#Seção " + r.nextInt(3) : "";
        String alias = r.nextInt(4) == 0 ? "|apelido " + r.nextInt(9) : "";
        return (r.nextInt(8) == 0 ? "!" : "") + "[[" + base + section + alias + "]]";
    }

    // ------------------------------------------------------------------------------------------------ cenário financeiro e criação

    private record Entities(String tx, String goal, String person, String debt, String invoice) {
    }

    private Entities entities(Actor actor) throws Exception {
        String tx = CardQaWorld.ok(actor.purchase("1200.00", 3, "2026-10-10", "Notebook")).node().get("id").asString();
        String goal = GoalQa.goal(actor, "Reserva", "20000.00", null);
        String person = DebtQa.person(actor, "Maria");
        String debt = CardQaWorld.ok(actor.call(HttpMethod.POST, "/v1/debts", "{\"personId\":\"" + person + "\",\"direction\":\"PAYABLE\",\"principal\":\"200.00\","
                + "\"date\":\"2026-10-10\",\"description\":\"Almoço\"}", null)).node().get("id").asString();
        String invoice = jdbc.queryForObject("select id from credit_card_invoices where user_id = ? limit 1", String.class, actor.id);
        return new Entities(tx, goal, person, debt, invoice);
    }

    private String explicitLinks(Random r, Entities e, Actor actor, String noteTarget) {
        List<String> links = new ArrayList<>();
        String[] pool = {NoteQa.link("TRANSACTION", e.tx), NoteQa.link("GOAL", e.goal), NoteQa.link("PERSON", e.person), NoteQa.link("DEBT", e.debt),
                NoteQa.link("INVOICE", e.invoice), NoteQa.link("CARD", actor.card), NoteQa.link("ACCOUNT", actor.nubank), NoteQa.monthLink("2026-10"),
                NoteQa.monthLink("2026-0" + (1 + r.nextInt(9)))};
        for (int i = 0; i < 1 + r.nextInt(4); i++) {
            String l = pool[r.nextInt(pool.length)];
            if (!links.contains(l)) {
                links.add(l);
            }
        }
        if (noteTarget != null) {
            links.add(NoteQa.link("NOTE", noteTarget));
        }
        return "[" + String.join(",", links) + "]";
    }

    /** Cria as notas (sem conferir contrato, por velocidade); devolve path -> id das criadas. Recusas do domínio (frontmatter inválido) são contadas. */
    private Map<String, String> createAll(Actor actor, List<Gen> gens, Entities e, Random r, int[] rejected) throws Exception {
        Map<String, String> ids = new LinkedHashMap<>();
        for (Gen g : gens) {
            String links = r.nextInt(4) == 0 ? ",\"links\":" + explicitLinks(r, e, actor, null) : "";
            var req = actor.req(HttpMethod.POST, "/v1/notes", "{\"path\":" + NoteQa.json(w, g.path()) + ",\"body\":" + NoteQa.json(w, g.text()) + links + "}");
            var res = mvc.perform(req).andReturn().getResponse();
            if (res.getStatus() == 201) {
                ids.put(g.path(), om.readTree(res.getContentAsString()).get("id").asString());
            } else {
                assertThat(res.getStatus()).as("%s: %s", g.path(), res.getContentAsString()).isEqualTo(422);
                rejected[0]++;
            }
        }
        // vínculos explícitos entre notas (por id): um terço das notas ganha um ou dois
        List<String> idList = new ArrayList<>(ids.values());
        for (Map.Entry<String, String> n : ids.entrySet()) {
            if (r.nextInt(3) != 0) {
                continue;
            }
            JsonNode note = om.readTree(mvc.perform(actor.req(HttpMethod.GET, "/v1/notes/" + n.getValue(), null)).andReturn().getResponse().getContentAsString());
            List<String> keep = new ArrayList<>();
            for (JsonNode l : note.get("links")) {
                if (l.get("origin").asString().equals("EXPLICIT")) {
                    keep.add(om.writeValueAsString(l.get("target").has("key") && !l.get("target").get("key").isNull()
                            ? Map.of("type", l.get("type").asString(), "key", l.get("target").get("key").asString())
                            : Map.of("type", l.get("type").asString(), "id", l.get("target").get("id").asString())));
                }
            }
            for (int k = 0; k < 1 + r.nextInt(2); k++) {
                String target = idList.get(r.nextInt(idList.size()));
                if (!target.equals(n.getValue())) {
                    keep.add(NoteQa.link("NOTE", target));
                }
            }
            var res = mvc.perform(actor.req(HttpMethod.PUT, "/v1/notes/" + n.getValue() + "/links",
                    "{\"links\":[" + String.join(",", keep) + "],\"version\":" + note.get("version").asLong() + "}")).andReturn().getResponse();
            assertThat(res.getStatus()).as("vincular nota: %s", res.getContentAsString()).isEqualTo(200);
        }
        return ids;
    }

    // ------------------------------------------------------------------------------------------------ fotografia do vault

    /** path -> descrição completa (corpo, título, tags e vínculos, com notas-alvo pelo CAMINHO), para comparar vaults com ids diferentes. */
    private Map<String, String> snapshot(Actor actor) {
        Map<String, String> out = new TreeMap<>();
        List<Map<String, Object>> notes = jdbc.queryForList("select id, path, title, body from notes where user_id = ? and deleted_at is null", actor.id);
        for (Map<String, Object> n : notes) {
            UUID id = (UUID) n.get("id");
            List<String> tags = jdbc.queryForList("select tag from note_tags where note_id = ? order by tag", String.class, id);
            List<String> links = jdbc.query("""
                    select l.origin, l.target_type, l.target_text, l.alias, l.section, l.embed, l.target_key, coalesce(t.path, '') as tpath,
                      coalesce(l.target_transaction_id::text, l.target_account_id::text, l.target_card_id::text, l.target_invoice_id::text,
                               l.target_person_id::text, l.target_debt_id::text, l.target_goal_id::text, '') as ent
                    from note_links l left join notes t on t.id = l.target_note_id where l.note_id = ? order by l.position""",
                    (rs, i) -> rs.getString("origin") + "|" + rs.getString("target_type") + "|" + rs.getString("target_text") + "|" + rs.getString("alias") + "|"
                            + rs.getString("section") + "|" + rs.getBoolean("embed") + "|" + String.valueOf(rs.getString("target_key")).strip() + "|" + rs.getString("tpath") + "|" + rs.getString("ent"),
                    id);
            List<String> sorted = new ArrayList<>(links);
            java.util.Collections.sort(sorted);
            out.put((String) n.get("path"), "title=" + n.get("title") + "\ntags=" + tags + "\nlinks=" + sorted + "\nbody=" + n.get("body"));
        }
        return out;
    }

    private Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), zip.readAllBytes());
            }
        }
        return files;
    }

    private static String shortDiff(String x, String y) {
        int i = 0;
        while (i < x.length() && i < y.length() && x.charAt(i) == y.charAt(i)) {
            i++;
        }
        return "diverge em " + i + ": [" + esc(x.substring(Math.max(0, i - 20), Math.min(x.length(), i + 60))) + "] x [" + esc(y.substring(Math.max(0, i - 20), Math.min(y.length(), i + 60))) + "]";
    }

    private static String esc(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    // ------------------------------------------------------------------------------------------------ testes

    @Test
    @DisplayName("1.000 notas aleatórias (frontmatter, CRLF, código, wikilinks de todas as formas, vínculos explícitos): exportar, apagar tudo e importar devolve TUDO igual; exportar de novo gera os mesmos bytes")
    void roundTripMesmoUsuario() throws Exception {
        Random r = new Random(SEED);
        Entities e = entities(a);
        List<Gen> gens = generate(1000, r);
        int[] rejected = new int[1];
        Map<String, String> ids = createAll(a, gens, e, r, rejected);
        System.out.println("QA-E6-RT criadas=" + ids.size() + " recusadas pelo domínio=" + rejected[0]);
        Map<String, String> before = snapshot(a);
        assertThat(before).hasSize(ids.size());

        byte[] export1 = NoteQa.export(w, a);
        Map<String, byte[]> files1 = unzip(export1);
        assertThat(files1.keySet()).containsExactlyInAnyOrderElementsOf(before.keySet());
        System.out.println("QA-E6-RT zip exportado: " + export1.length + " bytes, " + files1.size() + " entradas");

        // nota sem vínculo explícito sai byte a byte igual ao que foi escrito
        List<String> diffs = new ArrayList<>();
        for (Gen g : gens) {
            if (!ids.containsKey(g.path())) {
                continue;
            }
            int explicit = jdbc.queryForObject("select count(*) from note_links where note_id = ?::uuid and origin = 'EXPLICIT'", Integer.class, ids.get(g.path()));
            if (explicit == 0 && !Arrays.equals(files1.get(g.path()), g.text().getBytes(StandardCharsets.UTF_8))) {
                diffs.add("EXPORT " + g.path() + ": " + shortDiff(g.text(), new String(files1.get(g.path()), StandardCharsets.UTF_8)));
            }
        }

        purge(a);
        Res imp = NoteQa.upload(w, a, export1, "SKIP");
        assertThat(imp.status()).as("%s", imp).isEqualTo(200);
        assertThat(imp.node().get("created").asInt()).isEqualTo(ids.size());
        System.out.println("QA-E6-RT relatório: skipped=" + imp.node().get("skipped") + " pending=" + imp.node().get("pendingLinks") + " warnings=" + imp.node().get("warnings").size()
                + " ignored=" + imp.node().get("ignoredFiles").size());
        Map<String, String> after = snapshot(a);
        assertThat(after.keySet()).isEqualTo(before.keySet());
        for (String path : before.keySet()) {
            if (!before.get(path).equals(after.get(path))) {
                diffs.add("IMPORT " + path + ": " + shortDiff(before.get(path), after.get(path)));
            }
        }
        byte[] export2 = NoteQa.export(w, a);
        Map<String, byte[]> files2 = unzip(export2);
        for (String path : files1.keySet()) {
            if (!Arrays.equals(files1.get(path), files2.get(path))) {
                diffs.add("RE-EXPORT " + path + ": " + shortDiff(new String(files1.get(path), StandardCharsets.UTF_8), String.valueOf(files2.get(path) == null ? null : new String(files2.get(path), StandardCharsets.UTF_8))));
            }
        }
        diffs.forEach(d -> System.out.println("QA-E6-RT DIFF " + d));
        assertThat(diffs).as("diferenças no round-trip (%d de %d notas)", diffs.size(), ids.size()).isEmpty();
        assertThat(w.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("round-trip para OUTRO usuário (as entidades financeiras não existem): corpo e tags idênticos, wikilinks iguais por caminho, vínculos NOTE e de mês voltam, vínculos de entidade viram pendentes e entram em pendingLinks")
    void roundTripOutroUsuario() throws Exception {
        Random r = new Random(SEED + 1);
        Entities e = entities(a);
        List<Gen> gens = generate(300, r);
        Map<String, String> ids = createAll(a, gens, e, r, new int[1]);
        Map<String, String> before = snapshot(a);
        byte[] zip = NoteQa.export(w, a);
        Actor b = w.new Actor("e6rtb");
        Res imp = NoteQa.upload(w, b, zip, "SKIP");
        assertThat(imp.status()).as("%s", imp).isEqualTo(200);
        assertThat(imp.node().get("created").asInt()).isEqualTo(ids.size());
        Map<String, String> after = snapshot(b);
        assertThat(after.keySet()).isEqualTo(before.keySet());
        List<String> diffs = new ArrayList<>();
        for (String path : before.keySet()) {
            String x = before.get(path);
            String y = after.get(path);
            // o corpo, o título e as tags são idênticos
            String bodyX = x.substring(x.indexOf("\nbody=") + 6);
            String bodyY = y.substring(y.indexOf("\nbody=") + 6);
            if (!bodyX.equals(bodyY)) {
                diffs.add("CORPO " + path + ": " + shortDiff(bodyX, bodyY));
            }
            String headX = x.substring(0, x.indexOf("\nlinks="));
            if (!headX.equals(y.substring(0, y.indexOf("\nlinks=")))) {
                diffs.add("TÍTULO/TAGS " + path);
            }
        }
        // nenhum vínculo explícito de entidade do usuário A aponta para algo do usuário B
        assertThat(jdbc.queryForObject("select count(*) from note_links where user_id = ? and (target_transaction_id is not null or target_goal_id is not null or target_person_id is not null"
                + " or target_debt_id is not null or target_invoice_id is not null or target_card_id is not null or target_account_id is not null)", Long.class, b.id)).isZero();
        long explicitEntity = jdbc.queryForObject("select count(*) from note_links where user_id = ? and origin = 'EXPLICIT' and target_type not in ('NOTE','MONTHLY_SUMMARY')", Long.class, a.id);
        long pendingExplicit = jdbc.queryForObject("select count(*) from note_links where user_id = ? and origin = 'EXPLICIT' and target_text is not null", Long.class, b.id);
        assertThat(pendingExplicit).as("vínculos de entidade de A viram pendentes em B").isEqualTo(explicitEntity);
        long noteLinksA = jdbc.queryForObject("select count(*) from note_links where user_id = ? and origin = 'EXPLICIT' and target_type in ('NOTE','MONTHLY_SUMMARY')", Long.class, a.id);
        long noteLinksB = jdbc.queryForObject("select count(*) from note_links where user_id = ? and origin = 'EXPLICIT' and target_type in ('NOTE','MONTHLY_SUMMARY') and target_text is null", Long.class, b.id);
        assertThat(noteLinksB).isEqualTo(noteLinksA);
        diffs.forEach(d -> System.out.println("QA-E6-RT2 DIFF " + d));
        assertThat(diffs).isEmpty();
    }

    @Test
    @DisplayName("BUG-QA-06: frontmatter vazio/em branco com vínculo explícito volta byte a byte")
    void bordasDoFrontmatterVazio() throws Exception {
        roundTripEdges(new String[][] {{"vazio", "---\n---\ncorpo"}, {"vazio sem corpo", "---\n---\n"}, {"em branco", "---\n\n---\ncorpo"}});
    }

    @Test
    @DisplayName("casos de borda do frontmatter com vínculo explícito: só comentário, CRLF, '...' no fim, sem quebra final, sem corpo, chave parecida com 'moneylog': o corpo volta byte a byte")
    void bordasDoFrontmatter() throws Exception {
        String[][] cases = {
                {"so comentario", "---\n# nada\n---\ncorpo"},
                {"crlf", "---\r\ntitle: x\r\ntags: [a]\r\n---\r\ncorpo\r\n"},
                {"reticencias", "---\ntitle: x\n...\ncorpo"},
                {"sem quebra final", "---\ntitle: x\n---"},
                {"sem corpo", "---\ntitle: x\n---\n"},
                {"chave parecida", "---\nmoneylog2: 1\nmoneylog_links: [a]\n---\ncorpo"},
                {"sem frontmatter", "só corpo\n"},
                {"corpo vazio", ""},
                {"regua depois", "texto\n---\noutra coisa\n---\n"},
                {"escalar multilinha", "---\ndesc: |\n  linha 1\n  linha 2\ntitle: z\n---\ncorpo"},
                {"ultima chave com lista", "---\ntags:\n  - a\n  - b\n---\ncorpo"},
                {"linhas finais em branco", "---\ntitle: x\n\n\n---\ncorpo"},
                {"espaco depois do fecho", "---\ntitle: x\n---   \ncorpo"},
                {"bom", "\uFEFFcom bom no inicio"},
        };
        roundTripEdges(cases);
    }

    private void roundTripEdges(String[][] cases) throws Exception {
        List<String> failures = new ArrayList<>();
        for (String[] c : cases) {
            Actor actor = w.new Actor("e6edge");
            String path = "borda.md";
            Res created = NoteQa.createWithLinks(w, actor, path, c[1], "[" + NoteQa.monthLink("2026-10") + "]");
            if (created.status() != 201) {
                failures.add(c[0] + ": criar -> " + created.status() + " " + created.code());
                continue;
            }
            byte[] zip = NoteQa.export(w, actor);
            String exported = new String(unzip(zip).get(path), StandardCharsets.UTF_8);
            purge(actor);
            Res imp = NoteQa.upload(w, actor, zip, "SKIP");
            if (imp.status() != 200 || imp.node().get("created").asInt() != 1) {
                failures.add(c[0] + ": importar -> " + imp);
                continue;
            }
            String body = jdbc.queryForObject("select body from notes where user_id = ? and path = ?", String.class, actor.id, path);
            long month = jdbc.queryForObject("select count(*) from note_links where user_id = ? and target_key = '2026-10'", Long.class, actor.id);
            if (!body.equals(c[1])) {
                failures.add(c[0] + ": corpo difere: " + shortDiff(c[1], body) + "  | exportado=[" + esc(exported) + "]");
            }
            if (month != 1) {
                failures.add(c[0] + ": vínculo do mês perdido");
            }
        }
        failures.forEach(f -> System.out.println("QA-E6-RT3 FALHA " + f));
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("S-275: notas na lixeira não vão no export, mas o vínculo explícito NOTE para nota da lixeira vai com state: trashed; esvaziar a lixeira remove o vínculo (decisão de produto adiada, documentada)")
    void lixeiraNaoVai() throws Exception {
        String keep = NoteQa.createOk(w, a, "fica.md", "fica");
        String gone = NoteQa.createOk(w, a, "lixo.md", "lixo");
        CardQaWorld.ok(NoteQa.setLinks(a, keep, "[" + NoteQa.link("NOTE", gone) + "," + NoteQa.monthLink("2026-10") + "]", 0));
        CardQaWorld.ok(NoteQa.trash(a, gone));
        Map<String, byte[]> files = unzip(NoteQa.export(w, a));
        assertThat(files.keySet()).containsExactly("fica.md");
        String text = new String(files.get("fica.md"), StandardCharsets.UTF_8);
        System.out.println("QA-E6-RT lixeira: fica.md exportada = [" + esc(text) + "]");
        assertThat(text).contains("MONTHLY_SUMMARY");
        assertThat(text).as("o vínculo para a nota da lixeira não deve sumir em silêncio do arquivo").contains("lixo.md");
        assertThat(text).contains("state: trashed");
        // enquanto a nota está na lixeira o vínculo continua no banco; restaurar a nota devolve o vínculo ao estado normal no arquivo
        assertThat(jdbc.queryForObject("select count(*) from note_links where note_id = ?::uuid and origin = 'EXPLICIT' and target_type = 'NOTE'", Long.class, keep)).isEqualTo(1L);
        // esvaziar a lixeira apaga a nota e, com ela, o vínculo (a decisão de produto fica adiada: ADR-009 e contrato)
        a.call(HttpMethod.DELETE, "/v1/notes/trash", null, null);
        assertThat(jdbc.queryForObject("select count(*) from note_links where note_id = ?::uuid and origin = 'EXPLICIT' and target_type = 'NOTE'", Long.class, keep)).isZero();
        assertThat(new String(unzip(NoteQa.export(w, a)).get("fica.md"), StandardCharsets.UTF_8)).doesNotContain("lixo.md");
    }
}
