package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T98: exportação CSV e backup JSON")
class GoalExportQaIT {

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
    private Actor a;
    private Actor b;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = world.new Actor("e5xa");
        b = world.new Actor("e5xb");
    }

    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == ';') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                i++;
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private MvcResult csv(Actor who, String query, String language) throws Exception {
        var req = MockMvcRequestBuilders.get("/v1/exports/transactions.csv" + query).header(HttpHeaders.AUTHORIZATION, who.bearer);
        if (language != null) {
            req.header(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        return mvc.perform(req).andReturn();
    }

    private static String body(MvcResult r) {
        byte[] bytes = r.getResponse().getContentAsByteArray();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Res expense(Actor who, String description, String accountId) throws Exception {
        return who.raw(HttpMethod.POST, "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"12.34\",\"date\":\"2026-10-05\",\"description\":\"" + description + "\",\"accountId\":\""
                + accountId + "\",\"categoryId\":\"" + who.food + "\",\"method\":\"PIX\"}", GoalQa.key());
    }

    @Test
    @DisplayName("injeção de fórmula: descrição, categoria, conta e cartão com =, +, -, @ (e tab/CR) saem com ' na frente; aspas, ponto e vírgula, Unicode e quebra de linha fazem ida e volta no parser RFC 4180")
    void csvInjecao() throws Exception {
        String formulaAccount = GoalQa.account(a, "=HYPERLINK(\\\"http://x\\\")", "CHECKING", "100.00");
        String plusCategory = a.category("+cat;com ponto e vírgula", CategoryKind.EXPENSE);
        String atCategory = a.category("@categoria", CategoryKind.EXPENSE);
        String minusCard = a.newCard("-cartão", 25, 5, "1000.00");
        List<String> descriptions = List.of("=1+1", "+SUM(A1:A2)", "-2+3", "@SUM(1)", "=cmd|' /C calc'!A0", " =1+1", "=\\\"=\\\"&\\\"x\\\"", "Café ñ 日本語 😀",
                "com \\\"aspas\\\" e; ponto-e-vírgula", "termina com ;", "\\\"\\\"", "'=já tem apóstrofo", "normal", "a,b", "=", "+", "-", "@", "|calc", "%d", "<script>alert(1)</script>");
        List<String> rejected = new ArrayList<>();
        for (String d : descriptions) {
            Res r = expense(a, d, a.nubank);
            if (r.status() != 201) {
                rejected.add(d + " -> " + r.status());
            }
        }
        for (String d : List.of("\\t=1+1", "\\r=1+1", "linha1\\nlinha2", "a\\u0000b")) {
            Res r = expense(a, d, a.nubank);
            System.out.println("QA-E5-CSV controle em descrição '" + d + "' -> " + r.status() + " " + r.code());
            if (r.status() >= 500) {
                rejected.add("5xx " + d);
            }
        }
        assertThat(rejected).as("só caracteres de controle podem ser recusados").isEmpty();
        assertThat(expense(a, "na conta fórmula", formulaAccount).status()).isEqualTo(201);
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"1.00\",\"date\":\"2026-10-05\",\"description\":\"cat+\",\"accountId\":\"" + a.nubank
                + "\",\"categoryId\":\"" + plusCategory + "\",\"method\":\"PIX\"}", GoalQa.key()));
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"2.00\",\"date\":\"2026-10-05\",\"description\":\"cat@\",\"accountId\":\"" + a.nubank
                + "\",\"categoryId\":\"" + atCategory + "\",\"method\":\"PIX\"}", GoalQa.key()));
        CardQaWorld.ok(a.purchaseOn(minusCard, a.tech, "3.00", 1, "2026-10-05", "no cartão"));
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", "{\"type\":\"TRANSFER\",\"amount\":\"4.00\",\"date\":\"2026-10-05\",\"description\":\"transf\",\"fromAccountId\":\"" + formulaAccount
                + "\",\"toAccountId\":\"" + a.nubank + "\"}", GoalQa.key()));

        MvcResult result = csv(a, "?from=2026-10-01&to=2026-10-31", null);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes[0] & 0xFF).isEqualTo(0xEF);
        assertThat(bytes[1] & 0xFF).isEqualTo(0xBB);
        assertThat(bytes[2] & 0xFF).isEqualTo(0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("﻿");
        assertThat(text).endsWith("\r\n");
        List<List<String>> rows = parse(text);
        long expected = jdbc.queryForObject("select count(*) from transactions where user_id = ? and status = 'ACTIVE' and transaction_date between '2026-10-01' and '2026-10-31'", Long.class, a.id);
        assertThat(rows).as("cabeçalho + uma linha por movimentação").hasSize((int) expected + 1);
        for (List<String> row : rows) {
            assertThat(row).as("colunas (8 + Direção, S-261 + Competência da renda, ADR-017)").hasSize(10);
        }
        List<String> offenders = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            for (int col : new int[] {2, 3, 4}) {
                String cell = row.get(col);
                if (!cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0) {
                    offenders.add("coluna " + col + ": " + cell);
                }
            }
            assertThat(row.get(0)).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(row.get(6)).matches("\\d+,\\d{2}");
            assertThat(row.get(1)).as("tipo traduzido (S-261)").isIn("Despesa", "Receita", "Transferência", "Investimento", "Empréstimo concedido", "Dinheiro recebido",
                    "Pagamento de dívida", "Compra no cartão", "Pagamento de fatura", "Reembolso", "Ajuste");
        }
        assertThat(offenders).as("células que começam com =, +, - ou @ sem neutralização").isEmpty();
        Set<String> cells = new HashSet<>();
        rows.subList(1, rows.size()).forEach(r -> cells.add(r.get(2)));
        for (String d : descriptions) {
            String original = d.replace("\\\"", "\"").strip();
            boolean dangerous = !original.isEmpty() && "=+-@".indexOf(original.charAt(0)) >= 0;
            assertThat(cells).as("descrição '" + original + "'").contains(dangerous ? "'" + original : original);
        }
        Set<String> wheres = new HashSet<>();
        Set<String> categories = new HashSet<>();
        rows.subList(1, rows.size()).forEach(r -> {
            wheres.add(r.get(4));
            categories.add(r.get(3));
        });
        assertThat(wheres).contains("'=HYPERLINK(\"http://x\")", "'-cartão", "'=HYPERLINK(\"http://x\") > Nubank");
        assertThat(categories).contains("'+cat;com ponto e vírgula", "'@categoria");
        assertThat(result.getResponse().getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment; filename=\"moneylog-transactions-2026-10-01_2026-10-31.csv\"");
        System.out.println("QA-E5-CSV nosniff=" + result.getResponse().getHeader("X-Content-Type-Options") + " csp=" + result.getResponse().getHeader("Content-Security-Policy"));
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).as("nosniff num download").isEqualTo("nosniff");
        MvcResult en = csv(a, "", "en-US,en;q=0.9");
        assertThat(body(en).substring(1)).startsWith("Date;Type;Description;Category;Account/Card;Method;Amount;Status;Direction;Income month\r\n");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).doesNotContain("\r").doesNotContain("\n");
        assertThat(csv(a, "", "pt-BR,pt;q=0.9").getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("Data;Tipo;Descrição");
        for (String lang : List.of("xx", "*", "!!!", "en;q=0.1, pt", "x".repeat(2000), "en-", "EN", "pt;q=abc")) {
            MvcResult r = csv(a, "", lang);
            assertThat(r.getResponse().getStatus()).as("Accept-Language '" + (lang.length() > 20 ? lang.substring(0, 20) : lang) + "'").isEqualTo(200);
        }
    }

    @Test
    @DisplayName("includeVoided, status=VOIDED, filtros inválidos e q hostil: estornadas só quando pedidas; filtro de outra conta vazio; nada 5xx")
    void csvFiltros() throws Exception {
        Res one = CardQaWorld.ok(expense(a, "ativa", a.nubank));
        Res two = CardQaWorld.ok(expense(a, "vai ser estornada", a.nubank));
        CardQaWorld.ok(a.voidTx(two.node().get("id").asString()));
        assertThat(parse(body(csv(a, "", null)).substring(1))).hasSize(2);
        assertThat(parse(body(csv(a, "?includeVoided=true", null)).substring(1))).hasSize(3);
        assertThat(parse(body(csv(a, "?status=VOIDED", null)).substring(1))).hasSize(2);
        assertThat(parse(body(csv(a, "?status=VOIDED&includeVoided=true", null)).substring(1))).as("status explícito vale mais que includeVoided (L3)").hasSize(2);
        assertThat(parse(body(csv(a, "?status=ACTIVE&includeVoided=true", null)).substring(1))).as("status=ACTIVE não é sobrescrito por includeVoided (L3)").hasSize(2);
        assertThat(parse(body(csv(a, "?accountId=" + b.nubank, null)).substring(1))).as("conta de outro usuário: só o cabeçalho").hasSize(1);
        assertThat(parse(body(csv(a, "?categoryId=" + b.food, null)).substring(1))).hasSize(1);
        assertThat(parse(body(csv(a, "?q=ativa", null)).substring(1))).hasSize(2);
        for (String q : List.of("%25", "_", "%27%20OR%201%3D1--", "%5C", "%22", "%3B", "%F0%9F%98%80", "x".repeat(100))) {
            assertThat(csv(a, "?q=" + q, null).getResponse().getStatus()).as("q=" + q).isLessThan(500);
        }
        for (String bad : List.of("?includeVoided=abc", "?from=2026-13-01", "?from=1999-12-31", "?to=2101-01-01", "?from=2026-10-31&to=2026-10-01", "?type=FOO", "?method=FOO", "?status=FOO",
                "?accountId=nao-uuid", "?categoryId=x", "?q=a%00b", "?q=" + "x".repeat(300), "?from=+999999999-12-31", "?from=", "?includeVoided=")) {
            MvcResult r = csv(a, bad, null);
            System.out.println("QA-E5-CSVQ " + bad.substring(0, Math.min(40, bad.length())) + " -> " + r.getResponse().getStatus());
            assertThat(r.getResponse().getStatus()).as(bad).isBetween(200, 499);
            if (r.getResponse().getStatus() >= 400) {
                assertThat(r.getResponse().getContentType()).as(bad + " erro como problem+json").contains("json");
                assertThat(r.getResponse().getContentAsString()).doesNotContain("﻿");
            }
        }
        for (String strict : List.of("?includeVoided=abc", "?from=2026-13-01", "?from=1999-12-31", "?to=2101-01-01", "?from=2026-10-31&to=2026-10-01", "?type=FOO", "?method=FOO", "?status=FOO",
                "?accountId=nao-uuid", "?q=" + "x".repeat(300))) {
            assertThat(csv(a, strict, null).getResponse().getStatus()).as(strict).isEqualTo(400);
        }
        assertThat(one.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("backup: contagens batem com o banco (inclusive arquivados, estornados, inativos), sem segredos, sem chaves de idempotência, sem e-mail e sem nenhum dado de outro usuário; goals[].reservations soma o current")
    void backup() throws Exception {
        String person = DebtQa.person(a, "João");
        CardQaWorld.ok(DebtQa.loan(a, "100.00", "2026-10-02", person, null));
        CardQaWorld.ok(a.purchase("600.00", 3, "2026-10-03", "Parcelada"));
        Res gone = CardQaWorld.ok(expense(a, "estornada", a.nubank));
        CardQaWorld.ok(a.voidTx(gone.node().get("id").asString()));
        String second = GoalQa.account(a, "Segunda", "CHECKING", "0.00");
        String goal = GoalQa.goal(a, "Meta backup", "1000.00", "2027-01-01");
        String idemKey = GoalQa.key();
        CardQaWorld.ok(GoalQa.reserveKey(a, goal, second, "10.00", LocalDate.of(2026, 10, 10), idemKey));
        CardQaWorld.ok(GoalQa.reserve(a, goal, a.nubank, "300.00", LocalDate.of(2026, 10, 10)));
        CardQaWorld.ok(GoalQa.reserve(a, goal, a.nubank, "-50.00", LocalDate.of(2026, 10, 12)));
        Res refused = a.call(HttpMethod.POST, "/v1/accounts/" + second + "/archive", null, null);
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("ACCOUNT_HAS_RESERVATIONS");
        CardQaWorld.ok(GoalQa.reserve(a, goal, second, "-10.00", LocalDate.of(2026, 10, 10)));
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/accounts/" + second + "/archive", null, null));
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "10.00", "Rec", a.food, DebtQa.accountTarget(a, "EXPENSE"), "MONTHLY", "2026-10-20", null), null));
        String secretHash = "f".repeat(60) + "beef";
        jdbc.update("insert into refresh_tokens (id,user_id,family_id,token_hash,created_at,updated_at,expires_at) values (?,?,?,?,?,?,?)", UUID.randomUUID(), a.id, UUID.randomUUID(), secretHash,
                Timestamp.from(Instant.parse("2026-10-10T12:00:00Z")), Timestamp.from(Instant.parse("2026-10-10T12:00:00Z")), Timestamp.from(Instant.parse("2026-11-10T12:00:00Z")));
        String bGoal = GoalQa.goal(b, "Meta de B", "500.00", null);
        CardQaWorld.ok(GoalQa.reserve(b, bGoal, b.nubank, "5.00", LocalDate.of(2026, 10, 10)));
        CardQaWorld.ok(expense(b, "despesa privada de B", b.nubank));
        DebtQa.person(b, "Pessoa de B");

        MvcResult result = mvc.perform(MockMvcRequestBuilders.get("/v1/exports/backup").header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        ContractValidation.conforms().match(result);
        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode backup = om.readTree(text);
        assertThat(backup.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment; filename=\"moneylog-backup-2026-10-10.json\"");
        assertThat(backup.get("accounts")).hasSize((int) a.count("accounts"));
        assertThat(backup.get("cards")).hasSize((int) a.count("credit_cards"));
        assertThat(backup.get("transactions")).as("inclui a estornada").hasSize((int) a.count("transactions"));
        assertThat(backup.get("debts")).hasSize((int) a.count("debts"));
        assertThat(backup.get("people")).hasSize((int) a.count("persons"));
        assertThat(backup.get("recurrences")).hasSize((int) a.count("recurring_transactions"));
        assertThat(backup.get("goals")).hasSize((int) a.count("goals"));
        assertThat(backup.get("categories")).hasSize((int) a.count("categories"));
        assertThat(backup.get("ledgerPostings")).as("inclui lançamentos inativos").hasSize((int) a.count("ledger_postings"));
        long reservationsInBackup = 0;
        for (JsonNode g : backup.get("goals")) {
            reservationsInBackup += g.get("reservations").size();
        }
        assertThat(reservationsInBackup).isEqualTo(jdbc.queryForObject("select count(*) from goal_reservations where user_id = ?", Long.class, a.id));
        assertThat(backup.get("accounts").toString()).as("conta arquivada continua no backup").contains("ARCHIVED");
        boolean voided = false;
        for (JsonNode t : backup.get("transactions")) {
            voided |= "VOIDED".equals(t.get("status").asString());
        }
        assertThat(voided).isTrue();
        String lower = text.toLowerCase();
        for (String forbidden : List.of(secretHash, idemKey, a.id.toString(), b.id.toString(), "despesa privada de b", "meta de b", "pessoa de b", b.nubank, bGoal, b.card, "jwt", "secret",
                "password", "senha", "token", "idempot", "refresh", "@example.com", "request_hash", "time_zone", "timezone")) {
            assertThat(lower).as("backup não pode conter '" + forbidden + "'").doesNotContain(forbidden.toLowerCase());
        }
        Set<String> keys = new HashSet<>();
        collectKeys(backup, keys);
        for (String key : keys) {
            assertThat(key.toLowerCase()).as("chave '" + key + "'").doesNotContainPattern("pass|hash|token|secret|idempot|email|jwt|userid|user_id");
        }
        long net = 0;
        for (JsonNode g : backup.get("goals")) {
            if (g.get("id").asString().equals(goal)) {
                for (JsonNode r : g.get("reservations")) {
                    net += CardQaWorld.cents(r.get("amount"));
                }
            }
        }
        assertThat(net).as("10,00 reservados e liberados + 300,00 - 50,00").isEqualTo(25_000);
        for (JsonNode account : backup.get("accounts")) {
            long sum = 0;
            for (JsonNode p : backup.get("ledgerPostings")) {
                if (p.get("active").asBoolean() && "ACCOUNT".equals(p.get("bookType").asString()) && p.get("bookId").asString().equals(account.get("id").asString())
                        && !LocalDate.parse(p.get("effectiveDate").asString()).isAfter(LocalDate.of(2026, 10, 10))) {
                    sum += CardQaWorld.cents(p.get("amount"));
                }
            }
            long api = CardQaWorld.cents(a.getOk("/v1/accounts/" + account.get("id").asString()).get("currentBalance"));
            assertThat(sum).as("saldo recalculado de " + account.get("name").asString()).isEqualTo(api);
        }
        String bText = b.get("/v1/exports/backup").text();
        assertThat(bText).doesNotContain(a.nubank).doesNotContain("Meta backup").doesNotContain("João");
    }

    @Test
    @DisplayName("BUG-QA-29: o dia do nome do arquivo de backup deve ser o de America/Sao_Paulo")
    void nomeDoBackupNoFuso() throws Exception {
        a.at(Instant.parse("2026-10-11T02:30:00Z"));
        MvcResult late = mvc.perform(MockMvcRequestBuilders.get("/v1/exports/backup").header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn();
        assertThat(late.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).as("às 23:30 de 10/10 em SP o dia é 10/10, não o de UTC (11/10)")
                .isEqualTo("attachment; filename=\"moneylog-backup-2026-10-10.json\"");
    }

    @Test
    @DisplayName("BUG-QA-31: q com caractere de controle deve ser 400 no CSV (a lista e o entries têm o mesmo defeito)")
    void qComControle() throws Exception {
        for (String q : List.of("a%00b", "a%0Ab", "a%09b", "%1Fa")) {
            MvcResult r = mvc.perform(MockMvcRequestBuilders.get(java.net.URI.create("/v1/exports/transactions.csv?q=" + q)).header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn();
            assertThat(r.getResponse().getStatus()).as("q=" + q).isEqualTo(400);
        }
    }

    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            node.properties().forEach(e -> {
                keys.add(e.getKey());
                collectKeys(e.getValue(), keys);
            });
        } else if (node.isArray()) {
            node.forEach(n -> collectKeys(n, keys));
        }
    }
}
