package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
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
@DisplayName("QA T82: segurança da Etapa 4")
class DebtSecurityQaIT {

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
    private String aPerson;
    private String aDebt;
    private String aLoanTx;
    private String aRec;
    private String aCardRec;
    private String bPerson;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = world.new Actor("e4sa");
        b = world.new Actor("e4sb");
        aPerson = DebtQa.person(a, "João");
        bPerson = DebtQa.person(b, "Maria");
        Res loan = CardQaWorld.ok(DebtQa.loan(a, "300.00", "2026-10-10", aPerson, "2026-11-05"));
        aLoanTx = loan.node().get("id").asString();
        aDebt = loan.node().get("debt").get("id").asString();
        aRec = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "119.00", "Internet", a.food,
                DebtQa.accountTarget(a, "EXPENSE"), "MONTHLY", "2026-10-10", null), null)).node().get("id").asString();
        aCardRec = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("CARD_PURCHASE", "49.90", "Streaming", a.tech,
                "\"cardId\":\"" + a.card + "\"", "MONTHLY", "2026-09-15", null), null)).node().get("id").asString();
    }

    private long snapshot() {
        return a.count("transactions") * 1_000_000 + a.count("debts") * 10_000 + a.count("persons") * 1000 + a.count("recurring_transactions") * 100
                + a.count("recurrence_skips") * 10 + a.count("ledger_postings") + a.ledgerSum();
    }

    private String putPerson(String id) {
        return "{\"name\":\"Hack\",\"version\":0}";
    }

    @Test
    @DisplayName("isolamento: B contra pessoas, dívidas, recorrências, ocorrências, realize, skip e movimentações de dívida de A: sempre 404 com o code do recurso; nada muda; listas não vazam")
    void matrizDeIsolamento() throws Exception {
        long before = snapshot();
        String putDebt = "{\"description\":\"Hack\",\"version\":0}";
        String putRec = "{\"amount\":\"1.00\",\"description\":\"Hack\",\"categoryId\":\"" + b.food + "\",\"accountId\":\"" + b.nubank + "\",\"method\":\"PIX\",\"version\":0}";
        String reason = "{\"reason\":\"x\"}";
        String expense = DebtQa.sharedExpenseBody(b, "100.00", "2026-10-10", List.of(DebtQa.shareJson("10.00", aPerson)));
        String loanWithAPerson = DebtQa.loanBody(b, "10.00", "2026-10-10", aPerson, null);
        String loanWithAAccount = "{\"type\":\"LOAN_GIVEN\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\"" + a.nubank
                + "\",\"newDebt\":{\"personId\":\"" + bPerson + "\"}}";
        String receivedWithADebt = DebtQa.receivedBody(b, "10.00", "2026-10-10", aDebt);
        String paymentWithADebt = "{\"type\":\"DEBT_PAYMENT\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\"" + b.nubank
                + "\",\"debtId\":\"" + aDebt + "\"}";
        String loanToADebt = "{\"type\":\"LOAN_GIVEN\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\"" + b.nubank
                + "\",\"debtId\":\"" + aDebt + "\"}";
        String recWithACategory = DebtQa.recurrenceBody("EXPENSE", "10.00", "x", a.food, DebtQa.accountTarget(b, "EXPENSE"), "MONTHLY", "2026-10-10", null);
        String recWithAAccount = DebtQa.recurrenceBody("EXPENSE", "10.00", "x", b.food, DebtQa.accountTarget(a, "EXPENSE"), "MONTHLY", "2026-10-10", null);
        String recWithACard = DebtQa.recurrenceBody("CARD_PURCHASE", "10.00", "x", b.tech, "\"cardId\":\"" + a.card + "\"", "MONTHLY", "2026-10-10", null);
        String debtWithAPerson = "{\"personId\":\"" + aPerson + "\",\"direction\":\"PAYABLE\",\"principal\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\"}";
        String debtWithACategory = "{\"personId\":\"" + bPerson + "\",\"direction\":\"PAYABLE\",\"principal\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"categoryId\":\""
                + a.food + "\"}";
        String realizeBody = "{}";
        Object[][] cases = {
                {HttpMethod.GET, "/v1/people/" + aPerson, null, "PERSON_NOT_FOUND"},
                {HttpMethod.PUT, "/v1/people/" + aPerson, putPerson(aPerson), "PERSON_NOT_FOUND"},
                {HttpMethod.POST, "/v1/people/" + aPerson + "/archive", null, "PERSON_NOT_FOUND"},
                {HttpMethod.POST, "/v1/people/" + aPerson + "/unarchive", null, "PERSON_NOT_FOUND"},
                {HttpMethod.GET, "/v1/debts/" + aDebt, null, "DEBT_NOT_FOUND"},
                {HttpMethod.PUT, "/v1/debts/" + aDebt, putDebt, "DEBT_NOT_FOUND"},
                {HttpMethod.POST, "/v1/debts/" + aDebt + "/cancel", reason, "DEBT_NOT_FOUND"},
                {HttpMethod.POST, "/v1/debts/" + aDebt + "/void", reason, "DEBT_NOT_FOUND"},
                {HttpMethod.POST, "/v1/debts", debtWithAPerson, "PERSON_NOT_FOUND"},
                {HttpMethod.POST, "/v1/debts", debtWithACategory, "CATEGORY_NOT_FOUND"},
                {HttpMethod.GET, "/v1/debts?personId=" + aPerson, null, null},
                {HttpMethod.GET, "/v1/recurrences/" + aRec, null, "RECURRENCE_NOT_FOUND"},
                {HttpMethod.PUT, "/v1/recurrences/" + aRec, putRec, "RECURRENCE_NOT_FOUND"},
                {HttpMethod.POST, "/v1/recurrences/" + aRec + "/end", "{}", "RECURRENCE_NOT_FOUND"},
                {HttpMethod.POST, "/v1/recurrences/" + aRec + "/deactivate", null, "RECURRENCE_NOT_FOUND"},
                {HttpMethod.POST, "/v1/recurrences/" + aRec + "/occurrences/2026-10-10/realize", realizeBody, "RECURRENCE_NOT_FOUND"},
                {HttpMethod.POST, "/v1/recurrences/" + aCardRec + "/occurrences/2026-10-15/realize", realizeBody, "RECURRENCE_NOT_FOUND"},
                {HttpMethod.POST, "/v1/recurrences/" + aRec + "/occurrences/2026-10-10/skip", null, "RECURRENCE_NOT_FOUND"},
                {HttpMethod.POST, "/v1/recurrences", recWithACategory, "CATEGORY_NOT_FOUND"},
                {HttpMethod.POST, "/v1/recurrences", recWithAAccount, "ACCOUNT_NOT_FOUND"},
                {HttpMethod.POST, "/v1/recurrences", recWithACard, "CARD_NOT_FOUND"},
                {HttpMethod.POST, "/v1/transactions", expense, "PERSON_NOT_FOUND"},
                {HttpMethod.POST, "/v1/transactions", loanWithAPerson, "PERSON_NOT_FOUND"},
                {HttpMethod.POST, "/v1/transactions", loanWithAAccount, "ACCOUNT_NOT_FOUND"},
                {HttpMethod.POST, "/v1/transactions", receivedWithADebt, "DEBT_NOT_FOUND"},
                {HttpMethod.POST, "/v1/transactions", paymentWithADebt, "DEBT_NOT_FOUND"},
                {HttpMethod.POST, "/v1/transactions", loanToADebt, "DEBT_NOT_FOUND"},
                {HttpMethod.POST, "/v1/transactions/" + aLoanTx + "/void", reason, "TRANSACTION_NOT_FOUND"},
                {HttpMethod.GET, "/v1/transactions/" + aLoanTx, null, "TRANSACTION_NOT_FOUND"},
        };
        List<String> failures = new ArrayList<>();
        for (Object[] c : cases) {
            String url = (String) c[1];
            boolean needsKey = c[0] == HttpMethod.POST && (url.equals("/v1/transactions") || url.contains("/realize"));
            Res r = b.raw((HttpMethod) c[0], url, (String) c[2], needsKey ? UUID.randomUUID().toString() : null);
            if (c[3] == null) {
                if (r.status() != 200 || r.node().get("items").size() != 0) {
                    failures.add(c[0] + " " + url + " -> " + r);
                }
            } else if (r.status() != 404 || !c[3].equals(r.code())) {
                failures.add(c[0] + " " + url + " -> " + r.status() + " " + r.code() + " (esperado 404 " + c[3] + ")");
            }
        }
        for (String list : List.of("/v1/people", "/v1/debts", "/v1/recurrences", "/v1/recurrences/occurrences?to=2027-12-31", "/v1/transactions", "/v1/dashboard/upcoming?to=2027-12-31")) {
            String text = b.get(list.startsWith("/v1/dashboard") ? list : list + (list.contains("?") ? "&" : "?") + "pageSize=50").text();
            for (String leaked : List.of(aPerson, aDebt, aRec, aCardRec, aLoanTx)) {
                if (text.contains(leaked)) {
                    failures.add("a lista " + list + " de B vaza o id " + leaked + " de A");
                }
            }
        }
        JsonNode summary = b.getOk("/v1/dashboard/summary");
        if (CardQaWorld.cents(summary.get("committed").get("d90")) != 0 || CardQaWorld.cents(summary.get("netWorth").get("receivables")) != 0) {
            failures.add("o resumo de B enxerga compromissos ou recebíveis de A: " + summary);
        }
        assertThat(failures).isEmpty();
        assertThat(snapshot()).as("nada mudou para A").isEqualTo(before);
        assertThat(world.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("sem token, token lixo e Origin hostil: 401 em todas as rotas novas (nunca 5xx)")
    void semToken() throws Exception {
        List<String> routes = List.of("GET /v1/people", "POST /v1/people", "GET /v1/people/" + aPerson, "PUT /v1/people/" + aPerson, "POST /v1/people/" + aPerson + "/archive",
                "POST /v1/people/" + aPerson + "/unarchive", "GET /v1/debts", "POST /v1/debts", "GET /v1/debts/" + aDebt, "PUT /v1/debts/" + aDebt,
                "POST /v1/debts/" + aDebt + "/cancel", "POST /v1/debts/" + aDebt + "/void", "GET /v1/recurrences", "POST /v1/recurrences", "GET /v1/recurrences/summary",
                "GET /v1/recurrences/occurrences", "GET /v1/recurrences/" + aRec, "PUT /v1/recurrences/" + aRec, "POST /v1/recurrences/" + aRec + "/end",
                "POST /v1/recurrences/" + aRec + "/deactivate", "POST /v1/recurrences/" + aRec + "/occurrences/2026-10-10/realize",
                "POST /v1/recurrences/" + aRec + "/occurrences/2026-10-10/skip", "POST /v1/transactions", "GET /v1/entries?competence=2026-10");
        String[] headers = {null, "Bearer lixo", "Bearer a.b.c", "Basic Zm9vOmJhcg=="};
        List<String> failures = new ArrayList<>();
        for (String route : routes) {
            String[] parts = route.split(" ", 2);
            for (String h : headers) {
                var req = MockMvcRequestBuilders.request(HttpMethod.valueOf(parts[0]), parts[1]).contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Origin", "https://evil.example").header("Idempotency-Key", UUID.randomUUID().toString());
                if (h != null) {
                    req.header(HttpHeaders.AUTHORIZATION, h);
                }
                int status = mvc.perform(req).andReturn().getResponse().getStatus();
                if (status != 401) {
                    failures.add(route + " [" + h + "] -> " + status);
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("campos desconhecidos (id, status, userId, version, settled, outstanding, origin...) em todos os corpos novos: 400 e nada gravado")
    void camposDesconhecidos() throws Exception {
        long before = snapshot();
        List<String> failures = new ArrayList<>();
        List<String> extras = List.of("\"id\":\"" + UUID.randomUUID() + "\"", "\"userId\":\"" + b.id + "\"", "\"status\":\"SETTLED\"", "\"settled\":\"1.00\"",
                "\"outstanding\":\"0.00\"", "\"origin\":\"WITH_MOVEMENT\"", "\"net\":\"1.00\"", "\"archived\":true", "\"active\":false", "\"frequency\":\"DAILY\"", "\"startDate\":\"2026-01-01\"");
        for (String extra : extras) {
            Object[][] bodies = {
                    {HttpMethod.POST, "/v1/people", "{\"name\":\"X\"," + extra + "}"},
                    {HttpMethod.PUT, "/v1/people/" + aPerson, "{\"name\":\"X\",\"version\":0," + extra + "}"},
                    {HttpMethod.POST, "/v1/debts", "{\"personId\":\"" + aPerson + "\",\"direction\":\"PAYABLE\",\"principal\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\"," + extra + "}"},
                    {HttpMethod.PUT, "/v1/debts/" + aDebt, "{\"description\":\"x\",\"version\":0," + extra + "}"},
                    {HttpMethod.POST, "/v1/debts/" + aDebt + "/cancel", "{\"reason\":\"x\"," + extra + "}"},
                    {HttpMethod.POST, "/v1/debts/" + aDebt + "/void", "{\"reason\":\"x\"," + extra + "}"},
                    {HttpMethod.PUT, "/v1/recurrences/" + aRec, "{\"amount\":\"1.00\",\"description\":\"x\",\"categoryId\":\"" + a.food + "\",\"accountId\":\"" + a.nubank
                            + "\",\"method\":\"PIX\",\"version\":0," + extra + "}"},
                    {HttpMethod.POST, "/v1/recurrences/" + aRec + "/end", "{" + extra + "}"},
                    {HttpMethod.POST, "/v1/recurrences/" + aRec + "/occurrences/2026-10-10/realize", "{" + extra + "}"},
                    {HttpMethod.POST, "/v1/transactions", "{\"type\":\"LOAN_GIVEN\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\"" + a.nubank
                            + "\",\"newDebt\":{\"personId\":\"" + aPerson + "\"}," + extra + "}"},
                    {HttpMethod.POST, "/v1/transactions", "{\"type\":\"LOAN_GIVEN\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\"" + a.nubank
                            + "\",\"newDebt\":{\"personId\":\"" + aPerson + "\"," + extra + "}}"},
                    {HttpMethod.POST, "/v1/transactions", DebtQa.sharedExpenseBody(a, "100.00", "2026-10-10",
                            List.of("{\"amount\":\"10.00\",\"newDebt\":{\"personId\":\"" + aPerson + "\"}," + extra + "}"))},
                    {HttpMethod.POST, "/v1/transactions", DebtQa.sharedExpenseBody(a, "100.00", "2026-10-10",
                            List.of("{\"amount\":\"10.00\",\"newDebt\":{\"personId\":\"" + aPerson + "\"," + extra + "}}"))},
            };
            for (Object[] c : bodies) {
                String url = (String) c[1];
                Res r = a.raw((HttpMethod) c[0], url, (String) c[2], url.equals("/v1/transactions") || url.contains("/realize") ? UUID.randomUUID().toString() : null);
                if (r.status() != 400) {
                    failures.add(c[0] + " " + url + " + " + extra + " -> " + r);
                }
            }
        }
        for (String extra : List.of("\"id\":\"" + UUID.randomUUID() + "\"", "\"active\":false", "\"version\":3", "\"nextOccurrence\":\"2026-10-10\"", "\"monthlyEstimate\":\"1.00\"", "\"installments\":3")) {
            Res r = a.raw(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "10.00", "x", a.food, DebtQa.accountTarget(a, "EXPENSE"), "MONTHLY", "2026-10-10", extra), null);
            if (r.status() != 400) {
                failures.add("POST /v1/recurrences + " + extra + " -> " + r);
            }
        }
        assertThat(failures).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("inteiros em texto, fracionários, científicos, nulos e fora do limite (interval, maxOccurrences, version): 400; nada gravado")
    void inteirosEstritos() throws Exception {
        long before = snapshot();
        List<String> failures = new ArrayList<>();
        for (String v : List.of("\"2\"", "2.5", "2.0", "1e0", "true", "[2]", "0", "-1", "1001")) {
            Res r = a.raw(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "10.00", "x", a.food, DebtQa.accountTarget(a, "EXPENSE"), "CUSTOM", "2026-10-10",
                    "\"interval\":" + v + ",\"unit\":\"WEEKS\""), null);
            if (r.status() != 400 && !(v.equals("null") && (r.status() == 422 || r.status() == 201))) {
                failures.add("interval=" + v + " -> " + r);
            }
        }
        for (String v : List.of("\"2\"", "2.5", "2.0", "1e0", "true", "[2]", "0", "-1", "10001")) {
            Res r = a.raw(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "10.00", "x", a.food, DebtQa.accountTarget(a, "EXPENSE"), "MONTHLY", "2026-10-10",
                    "\"maxOccurrences\":" + v), null);
            if (r.status() != 400) {
                failures.add("maxOccurrences=" + v + " -> " + r);
            }
        }
        for (String v : List.of("\"0\"", "0.5", "0.0", "1e0", "true", "null", "[0]")) {
            Res person = a.raw(HttpMethod.PUT, "/v1/people/" + aPerson, "{\"name\":\"X\",\"version\":" + v + "}", null);
            Res debt = a.raw(HttpMethod.PUT, "/v1/debts/" + aDebt, "{\"description\":\"X\",\"version\":" + v + "}", null);
            Res rec = a.raw(HttpMethod.PUT, "/v1/recurrences/" + aRec, "{\"amount\":\"1.00\",\"description\":\"x\",\"categoryId\":\"" + a.food + "\",\"accountId\":\"" + a.nubank
                    + "\",\"method\":\"PIX\",\"version\":" + v + "}", null);
            for (Res r : List.of(person, debt, rec)) {
                if (r.status() != 400) {
                    failures.add("version=" + v + " -> " + r);
                }
            }
        }
        for (String v : List.of("\"1e3\"", "\"-5.00\"", "\"0.00\"", "\"10.001\"", "\" \"", "\"1,00\"", "\"\"", "null", "\"NaN\"", "10", "\"٣.٠٠\"", "\"1000000000.00\"", "\"99999999999999999999.00\"")) {
            List<Res> rs = List.of(
                    a.raw(HttpMethod.POST, "/v1/debts", "{\"personId\":\"" + aPerson + "\",\"direction\":\"PAYABLE\",\"principal\":" + v + ",\"date\":\"2026-10-10\",\"description\":\"x\"}", null),
                    a.raw(HttpMethod.POST, "/v1/transactions", "{\"type\":\"LOAN_GIVEN\",\"amount\":" + v + ",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\"" + a.nubank
                            + "\",\"newDebt\":{\"personId\":\"" + aPerson + "\"}}", UUID.randomUUID().toString()),
                    a.raw(HttpMethod.POST, "/v1/transactions", DebtQa.sharedExpenseBody(a, "100.00", "2026-10-10", List.of("{\"amount\":" + v + ",\"newDebt\":{\"personId\":\"" + aPerson + "\"}}")),
                            UUID.randomUUID().toString()),
                    a.raw(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "x", "x", a.food, DebtQa.accountTarget(a, "EXPENSE"), "MONTHLY", "2026-10-10", null)
                            .replace("\"amount\":\"x\"", "\"amount\":" + v), null),
                    "null".equals(v) ? a.raw(HttpMethod.GET, "/v1/people/" + UUID.randomUUID(), null, null)
                            : a.raw(HttpMethod.POST, "/v1/recurrences/" + aRec + "/occurrences/2026-10-10/realize", "{\"amount\":" + v + "}", UUID.randomUUID().toString()));
            for (Res r : rs) {
                if (r.status() < 400 || r.status() >= 500) {
                    failures.add("valor hostil " + v + " -> " + r);
                }
            }
        }
        assertThat(failures).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("idempotência do realize: sem chave 400, chave não UUID 400, mesma chave e corpo = mesma movimentação, outro corpo/data 409; chave de outro usuário é independente")
    void idempotencia() throws Exception {
        String url = "/v1/recurrences/" + aRec + "/occurrences/2026-10-10/realize";
        assertThat(a.raw(HttpMethod.POST, url, "{}", null).status()).isEqualTo(400);
        assertThat(a.raw(HttpMethod.POST, url, "{}", "nao-e-uuid").status()).isEqualTo(400);
        String key = UUID.randomUUID().toString();
        Res first = CardQaWorld.ok(a.call(HttpMethod.POST, url, "{}", key));
        Res replay = CardQaWorld.ok(a.call(HttpMethod.POST, url, "{}", key));
        assertThat(replay.node().get("id").asString()).isEqualTo(first.node().get("id").asString());
        assertThat(a.call(HttpMethod.POST, url, "{\"amount\":\"130.00\"}", key).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(a.call(HttpMethod.POST, "/v1/recurrences/" + aRec + "/occurrences/2026-11-10/realize", "{}", key).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(a.count("transactions")).as("empréstimo + 1 realização").isEqualTo(2L);
        String loanKey = UUID.randomUUID().toString();
        Res l1 = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", DebtQa.loanBody(a, "10.00", "2026-10-10", aPerson, null), loanKey));
        Res l2 = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", DebtQa.loanBody(a, "10.00", "2026-10-10", aPerson, null), loanKey));
        assertThat(l2.node().get("id").asString()).isEqualTo(l1.node().get("id").asString());
        assertThat(a.call(HttpMethod.POST, "/v1/transactions", DebtQa.loanBody(a, "10.01", "2026-10-10", aPerson, null), loanKey).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        String shareKey = UUID.randomUUID().toString();
        String s1 = DebtQa.sharedExpenseBody(a, "100.00", "2026-10-10", List.of(DebtQa.shareJson("10.00", aPerson)));
        String s2 = DebtQa.sharedExpenseBody(a, "100.00", "2026-10-10", List.of(DebtQa.shareJson("10.01", aPerson)));
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", s1, shareKey));
        assertThat(a.call(HttpMethod.POST, "/v1/transactions", s2, shareKey).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        String bRec = CardQaWorld.ok(b.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "119.00", "Internet", b.food,
                DebtQa.accountTarget(b, "EXPENSE"), "MONTHLY", "2026-10-10", null), null)).node().get("id").asString();
        Res other = b.call(HttpMethod.POST, "/v1/recurrences/" + bRec + "/occurrences/2026-10-10/realize", "{}", key);
        assertThat(other.status()).isEqualTo(201);
        assertThat(other.node().get("id").asString()).isNotEqualTo(first.node().get("id").asString());
        assertThat(world.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("caracteres de controle e NUL em nome, notas, descrições, motivos e termo de busca: nunca 5xx; nada de NUL gravado")
    void caracteresDeControle() throws Exception {
        List<String> failures = new ArrayList<>();
        List<String> hostile = List.of("a\\u0000b", "\\u0001x", "x\\u001f", "tab\\there", "nova\\nlinha", "\\u202Eevil", "\\ud83d\\ude00", "\\ud800", "   ", "");
        for (String h : hostile) {
            List<Res> rs = new ArrayList<>();
            rs.add(a.raw(HttpMethod.POST, "/v1/people", "{\"name\":\"" + h + "\"}", null));
            rs.add(a.raw(HttpMethod.POST, "/v1/people", "{\"name\":\"ok\",\"notes\":\"" + h + "\"}", null));
            rs.add(a.raw(HttpMethod.POST, "/v1/debts", "{\"personId\":\"" + aPerson + "\",\"direction\":\"PAYABLE\",\"principal\":\"1.00\",\"date\":\"2026-10-10\",\"description\":\"" + h + "\"}", null));
            rs.add(a.raw(HttpMethod.PUT, "/v1/debts/" + aDebt, "{\"description\":\"" + h + "\",\"version\":0}", null));
            rs.add(a.raw(HttpMethod.POST, "/v1/debts/" + aDebt + "/void", "{\"reason\":\"" + h + "\"}", null));
            rs.add(a.raw(HttpMethod.POST, "/v1/transactions", "{\"type\":\"LOAN_GIVEN\",\"amount\":\"1.00\",\"date\":\"2026-10-10\",\"description\":\"" + h + "\",\"accountId\":\"" + a.nubank
                    + "\",\"newDebt\":{\"personId\":\"" + aPerson + "\",\"description\":\"" + h + "\"}}", UUID.randomUUID().toString()));
            rs.add(a.raw(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "10.00", h, a.food, DebtQa.accountTarget(a, "EXPENSE"), "MONTHLY", "2026-10-10", null), null));
            for (Res r : rs) {
                if (r.status() >= 500) {
                    failures.add("[" + h + "] -> " + r);
                }
            }
        }
        for (String q : List.of("%25", "_", "%00", "a%00b", "%27%20OR%201=1", "%ff", "x".repeat(5000))) {
            Res r = a.raw(HttpMethod.GET, "/v1/people?q=" + q, null, null);
            if (r.status() >= 500) {
                failures.add("?q=" + q.substring(0, Math.min(20, q.length())) + " -> " + r);
            }
        }
        Long nul = jdbc.queryForObject("select count(*) from persons where user_id = ? and (name like '%' || chr(1) || '%')", Long.class, a.id);
        assertThat(failures).isEmpty();
        assertThat(nul).isNotNull();
    }

    @Test
    @DisplayName("datas fora da janela 2000-2100 e inválidas em dívida, movimentação, recorrência, realize, skip e consultas: 400/422, nunca 5xx")
    void datasForaDaJanela() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String d : List.of("1999-12-31", "2101-01-01", "0000-01-01", "+999999999-12-31", "9999-12-31", "2026-02-30", "2026-13-01", "26-10-10", "2026-10-10T10:00:00", "")) {
            List<Res> rs = List.of(
                    a.raw(HttpMethod.POST, "/v1/debts", "{\"personId\":\"" + aPerson + "\",\"direction\":\"PAYABLE\",\"principal\":\"1.00\",\"date\":\"" + d + "\",\"description\":\"x\"}", null),
                    a.raw(HttpMethod.POST, "/v1/debts", "{\"personId\":\"" + aPerson + "\",\"direction\":\"PAYABLE\",\"principal\":\"1.00\",\"date\":\"2026-10-10\",\"expectedDate\":\"" + d + "\",\"description\":\"x\"}", null),
                    a.raw(HttpMethod.POST, "/v1/transactions", DebtQa.loanBody(a, "1.00", d, aPerson, null), UUID.randomUUID().toString()),
                    a.raw(HttpMethod.POST, "/v1/transactions", DebtQa.loanBody(a, "1.00", "2026-10-10", aPerson, d), UUID.randomUUID().toString()),
                    a.raw(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "10.00", "x", a.food, DebtQa.accountTarget(a, "EXPENSE"), "MONTHLY", d, null), null),
                    a.raw(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "10.00", "x", a.food, DebtQa.accountTarget(a, "EXPENSE"), "MONTHLY", "2026-10-10", "\"endDate\":\"" + d + "\""), null),
                    a.raw(HttpMethod.POST, "/v1/recurrences/" + aRec + "/occurrences/" + d + "/realize", "{}", UUID.randomUUID().toString()),
                    a.raw(HttpMethod.POST, "/v1/recurrences/" + aRec + "/occurrences/" + d + "/skip", null, null),
                    a.raw(HttpMethod.POST, "/v1/recurrences/" + aRec + "/end", "{\"endDate\":\"" + d + "\"}", null),
                    a.raw(HttpMethod.GET, "/v1/recurrences/occurrences?from=" + d, null, null),
                    a.raw(HttpMethod.GET, "/v1/recurrences/occurrences?to=" + d, null, null),
                    a.raw(HttpMethod.GET, "/v1/dashboard/upcoming?from=" + d, null, null),
                    a.raw(HttpMethod.GET, "/v1/dashboard/upcoming?to=" + d, null, null));
            for (Res r : rs) {
                if (r.status() >= 500) {
                    failures.add("data [" + d + "] -> " + r);
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("sonda: classifica os 5xx de datas extremas por rota e parâmetro")
    void sondaDeDatas() throws Exception {
        List<String> out = new ArrayList<>();
        for (String d : List.of("1999-12-31", "0000-01-01", "2101-01-01", "+999999999-12-31", "9999-12-31")) {
            for (String q : List.of("/v1/dashboard/upcoming?from=" + d, "/v1/dashboard/upcoming?to=" + d, "/v1/dashboard/upcoming?from=" + d + "&to=" + d,
                    "/v1/recurrences/occurrences?from=" + d, "/v1/recurrences/occurrences?to=" + d, "/v1/recurrences/occurrences?from=" + d + "&to=" + d,
                    "/v1/dashboard/summary?asOf=" + d, "/v1/entries?competence=" + d.substring(0, 7))) {
                Res r = a.raw(HttpMethod.GET, q, null, null);
                out.add(r.status() + " " + q + " " + r.code());
            }
        }
        out.forEach(l -> System.out.println("QA-E4-PROBE " + l));
    }
}
