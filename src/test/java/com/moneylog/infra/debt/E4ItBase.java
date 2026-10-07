package com.moneylog.infra.debt;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;
import com.moneylog.util.SqlFixtures;

/**
 * Base dos ITs da Etapa 4 (pessoas, dívidas, divisão de despesa e recorrências). Cenário padrão: Nubank CHECKING 20.000,00,
 * cartão Roxinho (limite 10.000,00, fecha 25, vence 5), categorias Alimentação, Assinaturas, Contas (despesa) e Salário (receita),
 * pessoas João e Maria; relógio 10/10/2026 em {@code America/Sao_Paulo}. Toda requisição e resposta é validada contra o contrato.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
public abstract class E4ItBase {

    protected static final Instant OCT_27 = Instant.parse("2026-10-27T15:00:00Z");
    protected static final Instant NOV_05 = Instant.parse("2026-11-05T15:00:00Z");

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

    protected UUID userId;
    protected String bearer;
    protected String nubank;
    protected String card;
    protected String food;
    protected String subscriptions;
    protected String bills;
    protected String salary;
    protected String joao;
    protected String maria;

    @BeforeEach
    void cenarioBase() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        userId = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, userId, "e4-" + userId + "@moneylog.com"));
        bearer = auth.bearer(userId);
        nubank = body(mockMvc.perform(post("/v1/accounts", "{\"name\":\"Nubank\",\"type\":\"CHECKING\",\"institution\":\"Nubank\","
                + "\"openingBalance\":{\"amount\":\"20000.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        card = body(mockMvc.perform(post("/v1/cards", "{\"name\":\"Roxinho\",\"institution\":\"Nubank\",\"limit\":{\"amount\":\"10000.00\","
                + "\"currency\":\"BRL\"},\"closingDay\":25,\"dueDay\":5,\"defaultPaymentAccountId\":\"" + nubank + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        food = category("Alimentação", CategoryKind.EXPENSE);
        subscriptions = category("Assinaturas", CategoryKind.EXPENSE);
        bills = category("Contas", CategoryKind.EXPENSE);
        salary = category("Salário", CategoryKind.INCOME);
        joao = person("João");
        maria = person("Maria");
    }

    // ---------------------------------------------------------------- relógio e transações

    /** Avança o relógio e renova o token (o JWT expira pelo relógio do contexto). */
    protected void advanceTo(Instant instant) {
        clock.set(instant);
        bearer = auth.bearer(userId);
    }

    protected void committed(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    // ---------------------------------------------------------------- requisições

    protected MockHttpServletRequestBuilder get(String url) {
        return MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, bearer);
    }

    protected MockHttpServletRequestBuilder post(String url, String json) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected MockHttpServletRequestBuilder put(String url, String json) {
        return MockMvcRequestBuilders.put(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected MockHttpServletRequestBuilder postAs(String otherBearer, String url, String json) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, otherBearer).contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    protected MockHttpServletRequestBuilder getAs(String otherBearer, String url) {
        return MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, otherBearer);
    }

    protected MockHttpServletRequestBuilder putAs(String otherBearer, String url, String json) {
        return MockMvcRequestBuilders.put(url).header(HttpHeaders.AUTHORIZATION, otherBearer).contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    protected ResultActions createTx(String json) throws Exception {
        return mockMvc.perform(post("/v1/transactions", json).header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** POST /v1/transactions esperando 201 e contrato conforme. */
    protected JsonNode created(String json) throws Exception {
        return body(createTx(json).andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
    }

    protected JsonNode okBody(MockHttpServletRequestBuilder request) throws Exception {
        return body(mockMvc.perform(request).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
    }

    protected JsonNode createdBody(MockHttpServletRequestBuilder request) throws Exception {
        return body(mockMvc.perform(request).andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
    }

    // ---------------------------------------------------------------- cenário

    protected String category(String name, CategoryKind kind) {
        Category category = Category.create(UUID.randomUUID(), userId, name, kind);
        committed(() -> SqlFixtures.insertCategory(jdbc, category));
        return category.id().toString();
    }

    protected String person(String name) throws Exception {
        return createdBody(post("/v1/people", "{\"name\":\"" + name + "\"}")).get("id").asString();
    }

    /** {@code POST /v1/transactions} de empréstimo ({@code LOAN_GIVEN}) à pessoa, devolvendo a movimentação. */
    protected JsonNode loan(String amount, String date, String personId, String expectedDate) throws Exception {
        return created("{\"type\":\"LOAN_GIVEN\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Empréstimo\","
                + "\"accountId\":\"" + nubank + "\",\"newDebt\":{\"personId\":\"" + personId + "\""
                + (expectedDate == null ? "" : ",\"expectedDate\":\"" + expectedDate + "\"") + "}}");
    }

    protected String received(String amount, String date, String debtId) {
        return "{\"type\":\"MONEY_RECEIVED\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Devolução\","
                + "\"accountId\":\"" + nubank + "\",\"debtId\":\"" + debtId + "\"}";
    }

    protected String payment(String amount, String date, String debtId) {
        return "{\"type\":\"DEBT_PAYMENT\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Pagamento\","
                + "\"accountId\":\"" + nubank + "\",\"debtId\":\"" + debtId + "\"}";
    }

    protected String voidBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    protected JsonNode debt(String id) throws Exception {
        return okBody(get("/v1/debts/" + id));
    }

    protected JsonNode person(String id, boolean detail) throws Exception {
        return okBody(get("/v1/people/" + id));
    }

    protected JsonNode summary() throws Exception {
        return okBody(get("/v1/dashboard/summary"));
    }

    protected JsonNode upcoming(String query) throws Exception {
        return okBody(get("/v1/dashboard/upcoming" + (query == null ? "" : "?" + query)));
    }

    protected String money(JsonNode node, String field) {
        return node.get(field).get("amount").asString();
    }

    protected String accountBalance() throws Exception {
        return okBody(get("/v1/accounts/" + nubank)).get("currentBalance").get("amount").asString();
    }

    protected long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Long.class, userId);
    }

    /** Σ (centavos) dos lançamentos ativos de um tipo de livro. */
    protected long book(String bookType) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE user_id = ? AND book_type = ? AND active",
                Long.class, userId, bookType);
    }

    /** O razão do usuário fecha em zero (partidas dobradas). */
    protected long ledgerTotal() {
        return jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE user_id = ? AND active", Long.class,
                userId);
    }
}
