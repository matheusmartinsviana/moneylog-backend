package com.moneylog.infra.resumao;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@Import(MutableClockConfig.class)
abstract class ResumaoItBase {

    protected static final Instant OCT_15 = Instant.parse("2026-10-15T15:00:00Z");

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ItAuth auth;
    @Autowired
    protected PlatformTransactionManager transactionManager;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase seedCategories;

    protected UUID userId;
    protected String bearer;
    protected String accountA;
    protected String cardA;
    protected String cardB;
    protected String catFood;
    protected String catSalary;
    protected String ana;
    protected String beto;

    @BeforeEach
    void usuarioNovo() {
        clock.set(OCT_15);
        newUser();
    }

    protected void basicWorld() throws Exception {
        accountA = createdBody(post("/v1/accounts", "{\"name\":\"Conta A\",\"type\":\"CHECKING\",\"institution\":\"Banco Exemplo\","
                + "\"openingBalance\":{\"amount\":\"10000.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).get("id").asString();
        cardA = createdBody(post("/v1/cards", "{\"name\":\"Cartao A\",\"institution\":\"Banco Exemplo\",\"limit\":{\"amount\":\"50000.00\","
                + "\"currency\":\"BRL\"},\"closingDay\":25,\"dueDay\":5,\"defaultPaymentAccountId\":\"" + accountA + "\"}")).get("id").asString();
        cardB = createdBody(post("/v1/cards", "{\"name\":\"Cartao B\",\"institution\":\"Banco Exemplo\",\"limit\":{\"amount\":\"50000.00\","
                + "\"currency\":\"BRL\"},\"closingDay\":20,\"dueDay\":10,\"defaultPaymentAccountId\":\"" + accountA + "\"}")).get("id").asString();
        catFood = createdBody(post("/v1/categories", "{\"name\":\"Mercado\",\"kind\":\"EXPENSE\"}")).get("id").asString();
        catSalary = createdBody(post("/v1/categories", "{\"name\":\"Renda mensal\",\"kind\":\"INCOME\"}")).get("id").asString();
        ana = createdBody(post("/v1/people", "{\"name\":\"Ana\"}")).get("id").asString();
        beto = createdBody(post("/v1/people", "{\"name\":\"Beto\"}")).get("id").asString();
    }

    protected String sharedPurchase(String card, String amount, int installments, String date, String splitMode, String sharesJson)
            throws Exception {
        return "{\"type\":\"CARD_PURCHASE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Compra\",\"cardId\":\""
                + card + "\",\"categoryId\":\"" + catFood + "\",\"installments\":" + installments
                + (splitMode == null ? "" : ",\"splitMode\":\"" + splitMode + "\",\"shares\":" + sharesJson) + "}";
    }

    protected String share(String personId, Integer bps) {
        return "{\"personId\":" + (personId == null ? "null" : "\"" + personId + "\"") + (bps == null ? "" : ",\"bps\":" + bps) + "}";
    }

    protected String amountShare(String personId, String amount) {
        return "{\"personId\":" + (personId == null ? "null" : "\"" + personId + "\"") + ",\"amount\":\"" + amount + "\"}";
    }

    protected void newUser() {
        userId = UUID.randomUUID();
        UUID created = userId;
        committed(() -> SqlFixtures.insertUser(jdbc, created, "resumao-" + created + "@example.com"));
        bearer = auth.bearer(userId);
        committed(() -> seedCategories.handle(new com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase.SeedSystemCategoriesCommand(created)));
    }

    protected String otherUserBearer() {
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@example.com"));
        return auth.bearer(other);
    }

    protected void advanceTo(Instant instant) {
        clock.set(instant);
        bearer = auth.bearer(userId);
    }

    protected void committed(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    protected MockHttpServletRequestBuilder get(String url) {
        return MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, bearer);
    }

    protected MockHttpServletRequestBuilder getAs(String otherBearer, String url) {
        return MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, otherBearer);
    }

    protected MockHttpServletRequestBuilder post(String url, String json) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected MockHttpServletRequestBuilder postAs(String otherBearer, String url, String json) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, otherBearer).contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    protected MockHttpServletRequestBuilder put(String url, String json) {
        return MockMvcRequestBuilders.put(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected MockHttpServletRequestBuilder putAs(String otherBearer, String url, String json) {
        return MockMvcRequestBuilders.put(url).header(HttpHeaders.AUTHORIZATION, otherBearer).contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    protected MockHttpServletRequestBuilder delete(String url) {
        return MockMvcRequestBuilders.delete(url).header(HttpHeaders.AUTHORIZATION, bearer);
    }

    protected MockHttpServletRequestBuilder idem(MockHttpServletRequestBuilder request) {
        return request.header("Idempotency-Key", UUID.randomUUID().toString());
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode okBody(MockHttpServletRequestBuilder request) throws Exception {
        return body(mockMvc.perform(request).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
    }

    protected JsonNode createdBody(MockHttpServletRequestBuilder request) throws Exception {
        return body(mockMvc.perform(request).andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
    }

    protected ResultActions createTx(String json) throws Exception {
        return mockMvc.perform(idem(post("/v1/transactions", json)));
    }

    protected JsonNode created(String json) throws Exception {
        return body(createTx(json).andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
    }

    protected String money(JsonNode node, String field) {
        return node.get(field).get("amount").asString();
    }

    protected long cents(JsonNode money) {
        return new java.math.BigDecimal(money.get("amount").asString()).movePointRight(2).longValueExact();
    }

    protected long cents(String amount) {
        return new java.math.BigDecimal(amount).movePointRight(2).longValueExact();
    }

    protected String errorCode(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("code").asString();
    }

    protected long ledgerTotal() {
        return jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE user_id = ? AND active", Long.class,
                userId);
    }

    protected long book(String bookType) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE user_id = ? AND book_type = ? AND active",
                Long.class, userId, bookType);
    }

    protected long book(String bookType, String bookId) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE user_id = ? AND book_type = ? AND book_id = ?::uuid AND active",
                Long.class, userId, bookType, bookId);
    }

    protected long bookOfTransaction(String transactionId, String bookType) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE user_id = ? AND transaction_id = ?::uuid "
                + "AND book_type = ? AND active", Long.class, userId, transactionId, bookType);
    }

    protected long totalOfTransaction(String transactionId) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE user_id = ? AND transaction_id = ?::uuid "
                + "AND active", Long.class, userId, transactionId);
    }

    protected long netWorthCents() {
        return book("ACCOUNT") + book("RECEIVABLE") + book("PAYABLE") + book("CARD");
    }

    protected List<Map<String, Object>> postings(String where, Object... args) {
        Object[] params = new Object[args.length + 1];
        params[0] = userId;
        System.arraycopy(args, 0, params, 1, args.length);
        return jdbc.queryForList("SELECT * FROM ledger_postings WHERE user_id = ? AND " + where + " ORDER BY created_at, id", params);
    }

    protected Map<Integer, Map<String, Long>> byInstallment(String transactionId) {
        Map<Integer, Map<String, Long>> result = new java.util.TreeMap<>();
        jdbc.query("SELECT ti.number AS number, p.book_type AS book, sum(p.amount_cents) AS cents FROM ledger_postings p "
                + "JOIN transaction_installments ti ON ti.id = p.installment_id WHERE p.user_id = ? AND p.transaction_id = ?::uuid AND p.active "
                + "GROUP BY ti.number, p.book_type", rs -> {
            result.computeIfAbsent(rs.getInt("number"), k -> new java.util.TreeMap<>()).put(rs.getString("book"), rs.getLong("cents"));
        }, userId, transactionId);
        return result;
    }

    protected long receivableOf(String transactionId, String personId) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE user_id = ? AND transaction_id = ?::uuid "
                + "AND book_type = 'RECEIVABLE' AND book_id = ?::uuid AND active", Long.class, userId, transactionId, personId);
    }

    protected long netWorthTotal() throws Exception {
        return cents(okBody(get("/v1/dashboard/summary")).get("netWorth").get("total"));
    }

    protected JsonNode receive(String personId, String transactionId, String amount, String accountId, int expectedStatus) throws Exception {
        String body = "{\"personId\":\"" + personId + "\",\"transactionId\":\"" + transactionId + "\",\"amount\":\"" + amount + "\","
                + (accountId == null ? "" : "\"accountId\":\"" + accountId + "\",") + "\"receivedOn\":\"2026-10-15\"}";
        MvcResult result = mockMvc.perform(idem(post("/v1/third-party-receipts", body))).andExpect(status().is(expectedStatus)).andReturn();
        return body(result);
    }

    protected JsonNode putShares(String transactionId, String splitMode, String sharesJson, long version, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(put("/v1/transactions/" + transactionId + "/shares",
                "{\"splitMode\":\"" + splitMode + "\",\"shares\":" + sharesJson + ",\"version\":" + version + "}"))
                .andExpect(status().is(expectedStatus)).andReturn();
        return body(result);
    }

    protected long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Long.class, userId);
    }

    protected Map<String, String> loadFixture(String name) throws Exception {
        JsonNode fixture;
        try (InputStream in = getClass().getResourceAsStream("/db/it/" + name + "/cenario.json")) {
            fixture = objectMapper.readTree(in);
        }
        clock.set(Instant.parse(fixture.get("relogio").asString()));
        bearer = auth.bearer(userId);
        Map<String, String> ids = new LinkedHashMap<>();
        for (JsonNode step : fixture.get("steps")) {
            String text = step.get("body").toString();
            for (Map.Entry<String, String> entry : ids.entrySet()) {
                text = text.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            String path = step.has("post") ? step.get("post").asString() : step.get("put").asString();
            MockHttpServletRequestBuilder request = step.has("post") ? post(path, text) : put(path, text);
            if (step.has("idempotent")) {
                request = idem(request);
            }
            MvcResult result = mockMvc.perform(request).andReturn();
            int httpStatus = result.getResponse().getStatus();
            if (httpStatus != 201 && httpStatus != 200) {
                throw new IllegalStateException("passo " + step.get("id").asString() + " falhou: " + httpStatus + " "
                        + result.getResponse().getContentAsString());
            }
            JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
            ids.put(step.get("id").asString(), response.get("id").asString());
            if (step.has("capture")) {
                for (Map.Entry<String, JsonNode> capture : step.get("capture").properties()) {
                    JsonNode node = response;
                    for (String part : capture.getValue().asString().split("\\.")) {
                        node = node.get(part);
                    }
                    ids.put(capture.getKey(), node.asString());
                }
            }
        }
        return ids;
    }
}
