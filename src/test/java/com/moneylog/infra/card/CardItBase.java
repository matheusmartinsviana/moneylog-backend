package com.moneylog.infra.card;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;
import com.moneylog.util.SqlFixtures;

/**
 * Base dos ITs de cartão da Etapa 3 (T54 em diante). Cenário padrão: Nubank CHECKING 20.000,00, cartão Roxinho (instituição
 * Nubank, limite 10.000,00, fecha 25, vence 5, conta padrão Nubank), categorias Alimentação, Tecnologia (despesa) e Salário
 * (receita); relógio 10/10/2026 em {@code America/Sao_Paulo}. Toda requisição e resposta validada contra o contrato.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
abstract class CardItBase {

    protected static final Instant OCT_27 = Instant.parse("2026-10-27T15:00:00Z");
    protected static final Instant OCT_30 = Instant.parse("2026-10-30T15:00:00Z");
    protected static final Instant NOV_05 = Instant.parse("2026-11-05T15:00:00Z");
    protected static final LocalDate NOV_CLOSING = LocalDate.of(2026, 10, 25);

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
    protected CardGateway cardGateway;
    @Autowired
    protected AccountGateway accountGateway;
    @Autowired
    protected TransactionGateway transactionGateway;
    @Autowired
    protected LedgerWriteGateway ledger;

    protected UUID userId;
    protected String bearer;
    protected String nubank;
    protected String card;
    protected String tech;
    protected String food;
    protected String income;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        userId = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, userId, "cartao-" + userId + "@moneylog.com"));
        bearer = auth.bearer(userId);
        nubank = body(mockMvc.perform(post("/v1/accounts", "{\"name\":\"Nubank\",\"type\":\"CHECKING\",\"institution\":\"Nubank\","
                + "\"openingBalance\":{\"amount\":\"20000.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        card = createCard("Roxinho", 25, 5);
        tech = category("Tecnologia", CategoryKind.EXPENSE);
        food = category("Alimentação", CategoryKind.EXPENSE);
        income = category("Salário", CategoryKind.INCOME);
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

    /** GET como outro usuário (o {@code Authorization} é o dele, não o do cenário). */
    protected MockHttpServletRequestBuilder getAs(String otherBearer, String url) {
        return MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, otherBearer);
    }

    protected MockHttpServletRequestBuilder postAs(String otherBearer, String url, String json) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, otherBearer).contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    protected MockHttpServletRequestBuilder post(String url, String json) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected MockHttpServletRequestBuilder put(String url, String json) {
        return MockMvcRequestBuilders.put(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected ResultActions createTx(String json) throws Exception {
        return mockMvc.perform(post("/v1/transactions", json).header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode created(String json) throws Exception {
        return body(createTx(json).andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
    }

    protected JsonNode okBody(MockHttpServletRequestBuilder request) throws Exception {
        return body(mockMvc.perform(request).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
    }

    // ---------------------------------------------------------------- cenário

    protected String createCard(String name, int closingDay, int dueDay) throws Exception {
        return body(mockMvc.perform(post("/v1/cards", "{\"name\":\"" + name + "\",\"institution\":\"Nubank\",\"limit\":{\"amount\":\"10000.00\","
                + "\"currency\":\"BRL\"},\"closingDay\":" + closingDay + ",\"dueDay\":" + dueDay + ",\"defaultPaymentAccountId\":\"" + nubank
                + "\"}")).andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    protected String category(String name, CategoryKind kind) {
        Category category = Category.create(UUID.randomUUID(), userId, name, kind);
        committed(() -> SqlFixtures.insertCategory(jdbc, category));
        return category.id().toString();
    }

    protected String purchase(String amount, int installments, String date, String description) {
        return purchaseOn(card, tech, amount, installments, date, description);
    }

    protected String purchaseOn(String cardId, String categoryId, String amount, int installments, String date, String description) {
        return "{\"type\":\"CARD_PURCHASE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description
                + "\",\"cardId\":\"" + cardId + "\",\"categoryId\":\"" + categoryId + "\",\"installments\":" + installments + "}";
    }

    protected String cardRefund(String amount, String date, String description) {
        return "{\"type\":\"REFUND\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description
                + "\",\"cardId\":\"" + card + "\",\"categoryId\":\"" + tech + "\"}";
    }

    /** As duas compras do cenário dos critérios, ambas em 10/10: 1.200,00 em 6x ("Notebook") e 1.083,00 à vista ("Mercado"). */
    protected void twoPurchases() throws Exception {
        created(purchase("1200.00", 6, "2026-10-10", "Notebook"));
        created(purchase("1083.00", 1, "2026-10-10", "Mercado"));
    }

    protected JsonNode cardNow() throws Exception {
        return okBody(get("/v1/cards/" + card));
    }

    protected UUID invoiceId(LocalDate closingDate) {
        return InvoiceIds.of(UUID.fromString(card), closingDate);
    }

    protected JsonNode invoice(LocalDate closingDate) throws Exception {
        return okBody(get("/v1/invoices/" + invoiceId(closingDate)));
    }

    protected long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Long.class, userId);
    }

    protected String money(JsonNode node, String field) {
        return node.get(field).get("amount").asString();
    }

    /**
     * Semeia o pagamento de uma fatura pelo domínio e pelo gateway (estado montado por fábrica, não pelo endpoint, E3-13).
     */
    protected Transaction seedPayment(String amount, LocalDate closing, LocalDate date) {
        CreditCard domainCard = cardGateway.findById(userId, UUID.fromString(card)).orElseThrow().value();
        Account account = accountGateway.findById(userId, UUID.fromString(nubank)).orElseThrow().value();
        List<Transaction> existing = transactionGateway.findAllOfCard(userId, domainCard.id());
        Transaction payment = CardTransactions.invoicePayment(TransactionHeader.of(UUID.randomUUID(), userId, date, "Fatura"),
                Money.of(amount), account, domainCard, closing, CardInvoices.chainFor(domainCard, existing), date);
        committed(() -> {
            transactionGateway.insert(payment);
            ledger.append(userId, payment.entries());
        });
        return payment;
    }

    // ---------------------------------------------------------------- pagamento de fatura pelo endpoint (T55 em diante)

    protected static String paymentBody(String accountId, String amount, String date) {
        return "{\"accountId\":\"" + accountId + "\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\"}";
    }

    protected ResultActions payInvoice(UUID invoiceId, String accountId, String amount, String date) throws Exception {
        return mockMvc.perform(post("/v1/invoices/" + invoiceId + "/payments", paymentBody(accountId, amount, date))
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    /** Paga e devolve a fatura atualizada (200 e contrato conferidos). */
    protected JsonNode paid(LocalDate closing, String amount, String date) throws Exception {
        return body(payInvoice(invoiceId(closing), nubank, amount, date).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
    }

    protected JsonNode account(String id) throws Exception {
        return okBody(get("/v1/accounts/" + id));
    }
}
