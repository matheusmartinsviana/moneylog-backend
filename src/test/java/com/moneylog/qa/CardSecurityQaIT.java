package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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

import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T61: segurança das rotas de cartão")
class CardSecurityQaIT {

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
    private CardQaWorld.Actor a;
    private CardQaWorld.Actor b;
    private String aPurchase;
    private UUID aInvoice;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = world.new Actor("sa");
        b = world.new Actor("sb");
        aPurchase = CardQaWorld.ok(a.purchase("1200.00", 6, "2026-10-10", "Notebook")).node().get("id").asString();
        aInvoice = a.invoiceId(LocalDate.of(2026, 10, 25));
    }

    private long snapshot() {
        return a.count("transactions") * 1_000_000 + a.count("transaction_installments") * 10_000 + a.count("credit_card_invoices") * 100
                + a.count("ledger_postings") + a.ledgerSum();
    }

    @Test
    @DisplayName("isolamento: B contra cartão, fatura, pagamento, prévias, compra, reembolso, cancelamento, estorno e edição de A: sempre 404 com o code do recurso, nada muda")
    void matrizDeIsolamento() throws Exception {
        long before = snapshot();
        String aCard = a.card;
        String purchaseBody = "{\"type\":\"CARD_PURCHASE\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"cardId\":\"" + aCard
                + "\",\"categoryId\":\"" + b.tech + "\",\"installments\":1}";
        String refundBody = "{\"type\":\"REFUND\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"cardId\":\"" + aCard
                + "\",\"categoryId\":\"" + b.tech + "\"}";
        String purchaseWithACategory = "{\"type\":\"CARD_PURCHASE\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"cardId\":\"" + b.card
                + "\",\"categoryId\":\"" + a.tech + "\",\"installments\":1}";
        String putCard = "{\"name\":\"Hack\",\"institution\":\"X\",\"limit\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},\"closingDay\":10,\"dueDay\":15,"
                + "\"defaultPaymentAccountId\":\"" + b.nubank + "\",\"version\":0}";
        String payA = "{\"accountId\":\"" + a.nubank + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\"}";
        String payB = "{\"accountId\":\"" + b.nubank + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\"}";
        String preview = "{\"amount\":\"10.00\",\"installments\":2,\"date\":\"2026-10-10\"}";
        UUID bInvoice = b.invoiceId(LocalDate.of(2026, 10, 25));
        List<String> failures = new ArrayList<>();
        Object[][] cases = {
                {HttpMethod.GET, "/v1/cards/" + aCard, null, "CARD_NOT_FOUND"},
                {HttpMethod.PUT, "/v1/cards/" + aCard, putCard, "CARD_NOT_FOUND"},
                {HttpMethod.POST, "/v1/cards/" + aCard + "/archive", null, "CARD_NOT_FOUND"},
                {HttpMethod.POST, "/v1/cards/" + aCard + "/unarchive", null, "CARD_NOT_FOUND"},
                {HttpMethod.GET, "/v1/cards/" + aCard + "/invoices", null, "CARD_NOT_FOUND"},
                {HttpMethod.GET, "/v1/cards/" + aCard + "/future-installments", null, "CARD_NOT_FOUND"},
                {HttpMethod.POST, "/v1/cards/" + aCard + "/purchase-preview", preview, "CARD_NOT_FOUND"},
                {HttpMethod.GET, "/v1/cards/" + aCard + "/billing-change-preview?closingDay=10&dueDay=5", null, "CARD_NOT_FOUND"},
                {HttpMethod.GET, "/v1/invoices/" + aInvoice, null, "INVOICE_NOT_FOUND"},
                {HttpMethod.GET, "/v1/invoices/" + aInvoice + "/payment-preview?amount=10.00", null, "INVOICE_NOT_FOUND"},
                {HttpMethod.POST, "/v1/invoices/" + aInvoice + "/payments", payB, "INVOICE_NOT_FOUND"},
                {HttpMethod.POST, "/v1/invoices/" + aInvoice + "/payments", payA, "INVOICE_NOT_FOUND"},
                {HttpMethod.POST, "/v1/invoices/" + bInvoice + "/payments", payA, "ACCOUNT_NOT_FOUND"},
                {HttpMethod.POST, "/v1/transactions", purchaseBody, "CARD_NOT_FOUND"},
                {HttpMethod.POST, "/v1/transactions", refundBody, "CARD_NOT_FOUND"},
                {HttpMethod.POST, "/v1/transactions", purchaseWithACategory, "CATEGORY_NOT_FOUND"},
                {HttpMethod.POST, "/v1/transactions/" + aPurchase + "/cancel-installments", "{\"reason\":\"x\"}", "TRANSACTION_NOT_FOUND"},
                {HttpMethod.POST, "/v1/transactions/" + aPurchase + "/void", "{\"reason\":\"x\"}", "TRANSACTION_NOT_FOUND"},
                {HttpMethod.GET, "/v1/transactions/" + aPurchase, null, "TRANSACTION_NOT_FOUND"},
                {HttpMethod.PUT, "/v1/transactions/" + aPurchase, "{\"type\":\"CARD_PURCHASE\",\"amount\":\"1200.00\",\"date\":\"2026-10-10\",\"description\":\"hack\","
                        + "\"cardId\":\"" + aCard + "\",\"categoryId\":\"" + b.tech + "\",\"installments\":6,\"version\":0}", "TRANSACTION_NOT_FOUND"},
        };
        for (Object[] c : cases) {
            CardQaWorld.Res r = b.raw((HttpMethod) c[0], (String) c[1], (String) c[2], c[0] == HttpMethod.POST && ((String) c[1]).startsWith("/v1/invoices")
                    || "/v1/transactions".equals(c[1]) ? UUID.randomUUID().toString() : null);
            if (r.status() != 404 || !c[3].equals(r.code())) {
                failures.add(c[0] + " " + c[1] + " -> " + r.status() + " " + r.code() + " (esperado 404 " + c[3] + ")");
            }
        }
        JsonNode list = b.getOk("/v1/cards?pageSize=50");
        list.get("items").forEach(i -> {
            if (i.get("id").asString().equals(aCard)) {
                failures.add("lista de cartões de B contém o cartão de A");
            }
        });
        assertThat(failures).isEmpty();
        assertThat(snapshot()).as("nada mudou para A").isEqualTo(before);
        assertThat(a.cardNow().get("name").asString()).isEqualTo("Roxinho");
        assertThat(world.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("sem token, token lixo e Origin hostil: 401 (e nunca 5xx) em todas as rotas novas")
    void semToken() throws Exception {
        List<String> failures = new ArrayList<>();
        Object[][] routes = {
                {HttpMethod.GET, "/v1/cards"}, {HttpMethod.POST, "/v1/cards"}, {HttpMethod.GET, "/v1/cards/" + a.card},
                {HttpMethod.PUT, "/v1/cards/" + a.card}, {HttpMethod.POST, "/v1/cards/" + a.card + "/archive"},
                {HttpMethod.POST, "/v1/cards/" + a.card + "/unarchive"}, {HttpMethod.GET, "/v1/cards/" + a.card + "/invoices"},
                {HttpMethod.GET, "/v1/cards/" + a.card + "/future-installments"}, {HttpMethod.POST, "/v1/cards/" + a.card + "/purchase-preview"},
                {HttpMethod.GET, "/v1/cards/" + a.card + "/billing-change-preview?closingDay=1&dueDay=2"}, {HttpMethod.GET, "/v1/invoices/" + aInvoice},
                {HttpMethod.GET, "/v1/invoices/" + aInvoice + "/payment-preview?amount=1.00"}, {HttpMethod.POST, "/v1/invoices/" + aInvoice + "/payments"},
                {HttpMethod.POST, "/v1/transactions/" + aPurchase + "/cancel-installments"}, {HttpMethod.GET, "/v1/dashboard/summary"},
                {HttpMethod.GET, "/v1/dashboard/upcoming"},
        };
        String[] headers = {null, "Bearer lixo", "Bearer a.b.c", "Basic Zm9vOmJhcg=="};
        for (Object[] route : routes) {
            for (String h : headers) {
                var req = MockMvcRequestBuilders.request((HttpMethod) route[0], (String) route[1]).contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Origin", "https://evil.example");
                if (h != null) {
                    req.header(HttpHeaders.AUTHORIZATION, h);
                }
                var res = mvc.perform(req).andReturn().getResponse();
                if (res.getStatus() != 401) {
                    failures.add(route[0] + " " + route[1] + " [" + h + "] -> " + res.getStatus());
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("campos desconhecidos (inclusive número de cartão, CVV, bandeira e finais) e id/status/version injetados: 400 e nada gravado")
    void camposDesconhecidos() throws Exception {
        long before = snapshot();
        long cards = a.count("credit_cards");
        List<String> failures = new ArrayList<>();
        String base = "\"name\":\"Novo\",\"institution\":\"X\",\"limit\":{\"amount\":\"100.00\",\"currency\":\"BRL\"},\"closingDay\":10,\"dueDay\":15";
        for (String extra : List.of("\"number\":\"4111111111111111\"", "\"cvv\":\"123\"", "\"brand\":\"VISA\"", "\"lastDigits\":\"1111\"", "\"cardNumber\":\"4111\"",
                "\"id\":\"" + UUID.randomUUID() + "\"", "\"status\":\"ARCHIVED\"", "\"userId\":\"" + b.id + "\"", "\"version\":5", "\"availableLimit\":{\"amount\":\"1.00\",\"currency\":\"BRL\"}")) {
            CardQaWorld.Res r = a.raw(HttpMethod.POST, "/v1/cards", "{" + base + "," + extra + "}", null);
            if (r.status() != 400) {
                failures.add("POST /v1/cards + " + extra + " -> " + r);
            }
        }
        for (String extra : List.of("\"number\":\"4111111111111111\"", "\"cvv\":\"123\"", "\"status\":\"ARCHIVED\"", "\"id\":\"" + UUID.randomUUID() + "\"")) {
            CardQaWorld.Res r = a.raw(HttpMethod.PUT, "/v1/cards/" + a.card, "{" + base + ",\"version\":0," + extra + "}", null);
            if (r.status() != 400) {
                failures.add("PUT /v1/cards + " + extra + " -> " + r);
            }
        }
        CardQaWorld.Res p1 = a.raw(HttpMethod.POST, "/v1/cards/" + a.card + "/purchase-preview", "{\"amount\":\"10.00\",\"installments\":1,\"date\":\"2026-10-10\",\"cvv\":\"1\"}", null);
        CardQaWorld.Res p2 = a.raw(HttpMethod.POST, "/v1/invoices/" + aInvoice + "/payments", "{\"accountId\":\"" + a.nubank + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"status\":\"PAID\"}",
                UUID.randomUUID().toString());
        CardQaWorld.Res p3 = a.raw(HttpMethod.POST, "/v1/transactions/" + aPurchase + "/cancel-installments", "{\"reason\":\"x\",\"all\":true}", null);
        CardQaWorld.Res p4 = a.raw(HttpMethod.POST, "/v1/transactions", "{\"type\":\"CARD_PURCHASE\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"cardId\":\"" + a.card
                + "\",\"categoryId\":\"" + a.tech + "\",\"installments\":1,\"accountId\":\"" + a.nubank + "\"}", UUID.randomUUID().toString());
        for (CardQaWorld.Res r : List.of(p1, p2, p3, p4)) {
            if (r.status() != 400) {
                failures.add("campo desconhecido aceito: " + r);
            }
        }
        assertThat(failures).isEmpty();
        assertThat(a.count("credit_cards")).isEqualTo(cards);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("nenhuma coluna de credit_cards (nem do JSON do Card) guarda número, CVV, bandeira ou finais")
    void semDadosSensiveisDeCartao() throws Exception {
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns where table_name in ('credit_cards','credit_card_billing_rules','credit_card_invoices')", String.class);
        assertThat(columns).isNotEmpty();
        assertThat(columns.stream().filter(c -> c.matches("(?i).*(number|numero|cvv|cvc|pan|brand|bandeira|digits|last4|holder|expir).*")).toList()).isEmpty();
        JsonNode card = a.cardNow();
        List<String> keys = new ArrayList<>();
        card.propertyNames().forEach(keys::add);
        assertThat(keys.stream().filter(c -> c.matches("(?i).*(number|cvv|cvc|brand|digits|holder|expir).*")).toList()).isEmpty();
        CardQaWorld.Res r = a.raw(HttpMethod.POST, "/v1/cards", "{\"name\":\"4111 1111 1111 1111\",\"limit\":{\"amount\":\"100.00\",\"currency\":\"BRL\"},\"closingDay\":10,\"dueDay\":15}", null);
        assertThat(r.status()).as(r.toString()).isLessThan(500);
    }

    @Test
    @DisplayName("números fracionários, notação científica e tipos errados em campo inteiro (closingDay, dueDay, installments, version): 400, nunca arredondados nem 5xx")
    void inteirosEstritos() throws Exception {
        long before = snapshot();
        long cards = a.count("credit_cards");
        List<String> failures = new ArrayList<>();
        String limit = "\"limit\":{\"amount\":\"100.00\",\"currency\":\"BRL\"}";
        for (String v : List.of("25.5", "25.0", "1e1", "true", "null", "[25]")) {
            CardQaWorld.Res r = a.raw(HttpMethod.POST, "/v1/cards", "{\"name\":\"I\"," + limit + ",\"closingDay\":" + v + ",\"dueDay\":5}", null);
            if (r.status() != 400) {
                failures.add("closingDay=" + v + " -> " + r);
            }
        }
        for (String v : List.of("2.5", "2.0", "1e0", "0", "37", "-1")) {
            CardQaWorld.Res r = a.raw(HttpMethod.POST, "/v1/transactions", "{\"type\":\"CARD_PURCHASE\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"cardId\":\"" + a.card
                    + "\",\"categoryId\":\"" + a.tech + "\",\"installments\":" + v + "}", UUID.randomUUID().toString());
            if (r.status() != 400) {
                failures.add("installments=" + v + " -> " + r);
            }
            CardQaWorld.Res p = a.raw(HttpMethod.POST, "/v1/cards/" + a.card + "/purchase-preview", "{\"amount\":\"10.00\",\"installments\":" + v + ",\"date\":\"2026-10-10\"}", null);
            if (p.status() != 400) {
                failures.add("preview installments=" + v + " -> " + p);
            }
        }
        CardQaWorld.Res ver = a.raw(HttpMethod.PUT, "/v1/cards/" + a.card, "{\"name\":\"I\"," + limit + ",\"closingDay\":25,\"dueDay\":5,\"version\":0.5}", null);
        if (ver.status() != 400) {
            failures.add("version=0.5 -> " + ver);
        }
        for (String amount : List.of("\"1e3\"", "\"-5.00\"", "\"0.00\"", "\"10.001\"", "\" 10.00\"", "\"10,00\"", "\"\"", "null", "\"٣.٠٠\"", "\"NaN\"", "\"999999999999999999.00\"")) {
            CardQaWorld.Res r = a.raw(HttpMethod.POST, "/v1/invoices/" + aInvoice + "/payments", "{\"accountId\":\"" + a.nubank + "\",\"amount\":" + amount + ",\"date\":\"2026-10-10\"}",
                    UUID.randomUUID().toString());
            if (r.status() >= 500 || r.status() == 200) {
                failures.add("pagamento amount=" + amount + " -> " + r);
            }
            CardQaWorld.Res t = a.raw(HttpMethod.POST, "/v1/transactions", "{\"type\":\"CARD_PURCHASE\",\"amount\":" + amount + ",\"date\":\"2026-10-10\",\"description\":\"x\",\"cardId\":\"" + a.card
                    + "\",\"categoryId\":\"" + a.tech + "\",\"installments\":1}", UUID.randomUUID().toString());
            if (t.status() >= 500 || t.status() == 201) {
                failures.add("compra amount=" + amount + " -> " + t);
            }
            CardQaWorld.Res q = a.raw(HttpMethod.GET, "/v1/invoices/" + aInvoice + "/payment-preview?amount=" + java.net.URLEncoder.encode(amount.replace("\"", ""), "UTF-8"), null, null);
            if (q.status() >= 500 || q.status() == 200) {
                failures.add("payment-preview amount=" + amount + " -> " + q);
            }
        }
        assertThat(failures).isEmpty();
        assertThat(a.count("credit_cards")).isEqualTo(cards);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("ids malformados e parâmetros hostis nas rotas novas: 400/404, nunca 5xx")
    void idsMalformados() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String id : List.of("abc", "123", "00000000-0000-0000-0000-000000000000", "%00", "..%2F..%2Fadmin", "1;drop table credit_cards")) {
            for (String path : List.of("/v1/cards/" + id, "/v1/cards/" + id + "/invoices", "/v1/invoices/" + id, "/v1/invoices/" + id + "/payment-preview?amount=1.00",
                    "/v1/cards/" + id + "/future-installments")) {
                CardQaWorld.Res r = a.raw(HttpMethod.GET, path, null, null);
                if (r.status() >= 500 || r.status() == 200) {
                    failures.add(path + " -> " + r.status());
                }
            }
        }
        for (String q : List.of("from=xxxx", "from=2026-13-40", "to=2026-02-30", "pageSize=0", "pageSize=51", "page=0", "pageSize=-1", "from=2026-10-01&to=2026-01-01", "status=XX")) {
            CardQaWorld.Res r = a.raw(HttpMethod.GET, "/v1/cards/" + a.card + "/invoices?" + q, null, null);
            if (r.status() >= 500) {
                failures.add("invoices?" + q + " -> " + r.status());
            }
        }
        for (String q : List.of("closingDay=0&dueDay=5", "closingDay=32&dueDay=5", "closingDay=x&dueDay=5", "closingDay=5", "closingDay=5.5&dueDay=2")) {
            CardQaWorld.Res r = a.raw(HttpMethod.GET, "/v1/cards/" + a.card + "/billing-change-preview?" + q, null, null);
            if (r.status() >= 500 || r.status() == 200) {
                failures.add("billing-change-preview?" + q + " -> " + r.status());
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("idempotência do pagamento: chave obrigatória e UUID; mesma chave + mesmo corpo = mesmo resultado; mesma chave + corpo diferente = 409 IDEMPOTENCY_KEY_REUSED; a chave de B não colide com a de A")
    void idempotenciaDoPagamento() throws Exception {
        CardQaWorld.ok(b.purchase("500.00", 1, "2026-10-10", "Mercado B"));
        a.at(java.time.Instant.parse("2026-10-30T15:00:00Z"));
        b.at(java.time.Instant.parse("2026-10-30T15:00:00Z"));
        String body = "{\"accountId\":\"" + a.nubank + "\",\"amount\":\"100.00\",\"date\":\"2026-10-30\"}";
        CardQaWorld.Res noKey = a.raw(HttpMethod.POST, "/v1/invoices/" + aInvoice + "/payments", body, null);
        assertThat(noKey.status()).as(noKey.toString()).isEqualTo(400);
        CardQaWorld.Res badKey = a.raw(HttpMethod.POST, "/v1/invoices/" + aInvoice + "/payments", body, "nao-e-uuid");
        assertThat(badKey.status()).as(badKey.toString()).isEqualTo(400);
        String key = UUID.randomUUID().toString();
        CardQaWorld.Res first = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/invoices/" + aInvoice + "/payments", body, key));
        CardQaWorld.Res replay = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/invoices/" + aInvoice + "/payments", body, key));
        assertThat(replay.node().get("payments")).hasSize(1);
        assertThat(replay.node().get("payments").get(0).get("transactionId")).isEqualTo(first.node().get("payments").get(0).get("transactionId"));
        CardQaWorld.Res other = a.raw(HttpMethod.POST, "/v1/invoices/" + aInvoice + "/payments", body.replace("100.00", "101.00"), key);
        assertThat(other.status()).as(other.toString()).isEqualTo(409);
        assertThat(other.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        CardQaWorld.Res otherInvoice = a.raw(HttpMethod.POST, "/v1/invoices/" + a.invoiceId(LocalDate.of(2026, 11, 25)) + "/payments", body, key);
        assertThat(otherInvoice.status()).as(otherInvoice.toString()).isEqualTo(409);
        String bBody = "{\"accountId\":\"" + b.nubank + "\",\"amount\":\"100.00\",\"date\":\"2026-10-30\"}";
        CardQaWorld.Res bRes = b.call(HttpMethod.POST, "/v1/invoices/" + b.invoiceId(LocalDate.of(2026, 10, 25)) + "/payments", bBody, key);
        assertThat(bRes.status()).as(bRes.toString()).isEqualTo(200);
        assertThat(a.getOk("/v1/invoices/" + aInvoice).get("payments")).hasSize(1);
        assertThat(world.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("BUG-QA-17: string numérica em campo inteiro deve ser 400")
    void stringNumericaEmCampoInteiro() throws Exception {
        CardQaWorld.Res r = a.raw(HttpMethod.POST, "/v1/cards", "{\"name\":\"S\",\"limit\":{\"amount\":\"100.00\",\"currency\":\"BRL\"},\"closingDay\":\"25\",\"dueDay\":5}", null);
        assertThat(r.status()).as(r.toString()).isEqualTo(400);
        CardQaWorld.Res t = a.raw(HttpMethod.POST, "/v1/cards/" + a.card + "/purchase-preview", "{\"amount\":\"10.00\",\"installments\":\"2\",\"date\":\"2026-10-10\"}", null);
        assertThat(t.status()).as(t.toString()).isEqualTo(400);
    }
}
