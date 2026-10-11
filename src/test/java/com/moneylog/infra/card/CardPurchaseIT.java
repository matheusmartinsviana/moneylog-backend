package com.moneylog.infra.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("Compra no cartão e prévia (T53)")
class CardPurchaseIT {

    private static final Instant OCT_27 = Instant.parse("2026-10-27T15:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;
    @Autowired
    private CardGateway cardGateway;
    @Autowired
    private AccountGateway accountGateway;
    @Autowired
    private TransactionGateway transactionGateway;
    @Autowired
    private LedgerWriteGateway ledger;

    private UUID userId;
    private String bearer;
    private String nubank;
    private String card;
    private String tech;
    private String income;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        userId = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, userId, "compra-" + userId + "@example.com"));
        bearer = auth.bearer(userId);
        nubank = body(mockMvc.perform(post("/v1/accounts", "{\"name\":\"Nubank\",\"type\":\"CHECKING\",\"institution\":\"Nubank\","
                + "\"openingBalance\":{\"amount\":\"20000.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        card = body(mockMvc.perform(post("/v1/cards", "{\"name\":\"Roxinho\",\"institution\":\"Nubank\",\"limit\":{\"amount\":\"10000.00\","
                + "\"currency\":\"BRL\"},\"closingDay\":25,\"dueDay\":5,\"defaultPaymentAccountId\":\"" + nubank + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        tech = category("Tecnologia", CategoryKind.EXPENSE);
        income = category("Salário", CategoryKind.INCOME);
    }

    private void advanceTo(Instant instant) {
        clock.set(instant);
        bearer = auth.bearer(userId);
    }

    private void committed(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private MockHttpServletRequestBuilder get(String url) {
        return MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, bearer);
    }

    private MockHttpServletRequestBuilder post(String url, String json) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private ResultActions createTx(String json) throws Exception {
        return mockMvc.perform(post("/v1/transactions", json).header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String category(String name, CategoryKind kind) {
        Category category = Category.create(UUID.randomUUID(), userId, name, kind);
        committed(() -> SqlFixtures.insertCategory(jdbc, category));
        return category.id().toString();
    }

    private String purchase(String amount, int installments, String date, String description) {
        return "{\"type\":\"CARD_PURCHASE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description
                + "\",\"cardId\":\"" + card + "\",\"categoryId\":\"" + tech + "\",\"installments\":" + installments + "}";
    }

    private JsonNode created(String json) throws Exception {
        return body(createTx(json).andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
    }

    private String preview(String amount, int installments, String date) {
        return "{\"amount\":\"" + amount + "\",\"installments\":" + installments + ",\"date\":\"" + date + "\"}";
    }

    private JsonNode cardNow() throws Exception {
        return body(mockMvc.perform(get("/v1/cards/" + card)).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Long.class, userId);
    }

    @Test
    @DisplayName("CA1: 1.200,00 em 6x: 201 com 6 parcelas de 200, faturas 25/10..25/03, caixa 0, consumo de 200 por competência, comprometido 1.000 e patrimônio −200")
    void deveCriarACompraParcelada() throws Exception {
        MvcResult result = createTx(purchase("1200.00", 6, "2026-10-10", "Notebook")).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.type").value("CARD_PURCHASE"))
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.amount.amount").value("1200.00"))
                .andExpect(jsonPath("$.description").value("Notebook")).andExpect(jsonPath("$.card.id").value(card))
                .andExpect(jsonPath("$.card.name").value("Roxinho")).andExpect(jsonPath("$.account").doesNotExist())
                .andExpect(jsonPath("$.category.name").value("Tecnologia")).andExpect(jsonPath("$.invoice").doesNotExist())
                .andExpect(jsonPath("$.installments.length()").value(6)).andExpect(jsonPath("$.installments[0].number").value(1))
                .andExpect(jsonPath("$.installments[0].total").value(6)).andExpect(jsonPath("$.installments[0].amount.amount").value("200.00"))
                .andExpect(jsonPath("$.installments[0].status").value("PENDING")).andExpect(jsonPath("$.installments[0].lateEntry").value(false))
                .andExpect(jsonPath("$.installments[0].competence").value("2026-10"))
                .andExpect(jsonPath("$.installments[0].invoiceReferenceMonth").value("2026-11"))
                .andExpect(jsonPath("$.installments[0].invoiceDueDate").value("2026-11-05"))
                .andExpect(jsonPath("$.installments[5].competence").value("2027-03"))
                .andExpect(jsonPath("$.installments[5].invoiceReferenceMonth").value("2027-04"))
                .andExpect(jsonPath("$.impact.cash.amount").value("0.00")).andExpect(jsonPath("$.impact.consumption.length()").value(6))
                .andExpect(jsonPath("$.impact.consumption[0].competence").value("2026-10"))
                .andExpect(jsonPath("$.impact.consumption[0].amount.amount").value("200.00"))
                .andExpect(jsonPath("$.impact.consumption[5].competence").value("2027-03"))
                .andExpect(jsonPath("$.impact.netWorth.amount").value("-200.00")).andExpect(jsonPath("$.impact.commitment.amount").value("1000.00"))
                .andExpect(jsonPath("$.editable").value(true)).andExpect(jsonPath("$.voidable").value(true))
                .andExpect(jsonPath("$.source").value("MANUAL")).andReturn();
        JsonNode created = body(result);
        UUID id = UUID.fromString(created.get("id").asString());

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/transactions/" + id);
        UUID cardId = UUID.fromString(card);
        assertThat(created.get("installments").get(0).get("invoiceId").asString())
                .isEqualTo(InvoiceIds.of(cardId, LocalDate.of(2026, 10, 25)).toString());
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE book_type = 'CARD' AND book_id = ? AND active",
                Long.class, cardId)).isEqualTo(-120_000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE transaction_id = ? AND book_type = 'ACCOUNT'", Long.class, id)).isZero();

        JsonNode afterwards = cardNow();
        assertThat(afterwards.get("availableLimit").get("amount").asString()).isEqualTo("8800.00");
        assertThat(afterwards.get("committedFuture").get("amount").asString()).isEqualTo("1000.00");
        assertThat(afterwards.get("totalCommitted").get("amount").asString()).isEqualTo("200.00");
        assertThat(afterwards.get("currentInvoice").get("total").get("amount").asString()).isEqualTo("200.00");
        assertThat(afterwards.get("usedRatio").asDouble()).isEqualTo(0.12);
        assertThat(afterwards.get("archivable").asBoolean()).isFalse();

        mockMvc.perform(get("/v1/transactions/" + id)).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.impact.commitment.amount").value("1000.00")).andExpect(jsonPath("$.installments.length()").value(6));
        mockMvc.perform(get("/v1/transactions?type=CARD_PURCHASE")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.totals.cashFlow.out.amount").value("0.00"))
                .andExpect(jsonPath("$.items[0].installments.length()").value(6));
        mockMvc.perform(get("/v1/entries?competence=2026-11")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].amount.amount").value("200.00"));
        mockMvc.perform(get("/v1/dashboard/summary")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.balances.inAccounts.amount").value("20000.00"));
        mockMvc.perform(get("/v1/dashboard/upcoming?from=2026-10-10&to=2027-04-30")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms());
        assertThat(count("credit_card_invoices")).isEqualTo(6);
        assertThat(count("transaction_installments")).isEqualTo(6);
    }

    @Test
    @DisplayName("CA2: 1.000,00 em 3x vira 333,34 / 333,33 / 333,33; à vista gera uma parcela e nenhum comprometido")
    void deveDividirEmCentavos() throws Exception {
        createTx(purchase("1000.00", 3, "2026-10-10", "Parcelado")).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.installments[0].amount.amount").value("333.34")).andExpect(jsonPath("$.installments[1].amount.amount").value("333.33"))
                .andExpect(jsonPath("$.installments[2].amount.amount").value("333.33"))
                .andExpect(jsonPath("$.impact.commitment.amount").value("666.66"));
        createTx(purchase("90.00", 1, "2026-10-10", "À vista")).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.installments.length()").value(1)).andExpect(jsonPath("$.impact.commitment.amount").value("0.00"))
                .andExpect(jsonPath("$.impact.consumption.length()").value(1));
        createTx("{\"type\":\"CARD_PURCHASE\",\"amount\":\"15.50\",\"date\":\"2026-10-10\",\"cardId\":\"" + card + "\",\"categoryId\":\"" + tech
                + "\",\"installments\":1}").andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.description").value(""));
    }

    @Test
    @DisplayName("CA3: relógio 27/10 e fatura de 25/10 fechada e não paga: a compra de 20/10 entra nela como lançamento tardio, e a prévia avisa LATE_ENTRY")
    void deveLancarComoTardioEmFaturaFechada() throws Exception {
        created(purchase("1200.00", 6, "2026-10-10", "Notebook"));
        created(purchase("1083.00", 1, "2026-10-12", "Mercado"));
        advanceTo(OCT_27);

        mockMvc.perform(post("/v1/cards/" + card + "/purchase-preview", preview("90.00", 1, "2026-10-20"))).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.warnings[0]").value("LATE_ENTRY"))
                .andExpect(jsonPath("$.installments[0].lateEntry").value(true))
                .andExpect(jsonPath("$.installments[0].invoice.closingDate").value("2026-10-25"))
                .andExpect(jsonPath("$.installments[0].invoice.status").value("CLOSED"));
        MvcResult late = createTx(purchase("90.00", 1, "2026-10-20", "Tardia")).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.installments[0].lateEntry").value(true))
                .andReturn();

        assertThat(body(late).get("installments").get(0).get("invoiceId").asString())
                .isEqualTo(InvoiceIds.of(UUID.fromString(card), LocalDate.of(2026, 10, 25)).toString());
        JsonNode afterwards = cardNow();
        assertThat(afterwards.get("totalCommitted").get("amount").asString()).isEqualTo("1573.00");
    }

    @Test
    @DisplayName("CA3: com a fatura de 25/10 já PAID, a parcela vai para a fatura corrente (25/11) com lançamento tardio e nunca para a paga")
    void deveMandarParaACorrenteSeAFaturaEstaPaga() throws Exception {
        created(purchase("1283.00", 1, "2026-10-10", "Mercado"));
        advanceTo(OCT_27);
        payInvoice("1283.00", LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26));

        createTx(purchase("90.00", 1, "2026-10-20", "Tardia")).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.installments[0].lateEntry").value(true))
                .andExpect(jsonPath("$.installments[0].invoiceReferenceMonth").value("2026-12"))
                .andExpect(jsonPath("$.installments[0].invoiceDueDate").value("2026-12-05"));
        assertThat(cardNow().get("currentInvoice").get("closingDate").asString()).isEqualTo("2026-11-25");
        assertThat(cardNow().get("currentInvoice").get("total").get("amount").asString()).isEqualTo("90.00");
    }

    private void payInvoice(String amount, LocalDate closing, LocalDate date) {
        CreditCard domainCard = cardGateway.findById(userId, UUID.fromString(card)).orElseThrow().value();
        Account account = accountGateway.findById(userId, UUID.fromString(nubank)).orElseThrow().value();
        List<Transaction> existing = transactionGateway.findAllOfCard(userId, domainCard.id());
        Transaction payment = CardTransactions.invoicePayment(TransactionHeader.of(UUID.randomUUID(), userId, date, "Fatura"),
                Money.of(amount), account, domainCard, closing, CardInvoices.chainFor(domainCard, existing), date);
        committed(() -> {
            transactionGateway.insert(payment);
            ledger.append(userId, payment.entries());
        });
    }

    @Test
    @DisplayName("CA4: a prévia devolve os mesmos valores, faturas e limite que a criação e não grava nada (nenhuma linha nova em lugar nenhum)")
    void devePrevisualizarSemGravar() throws Exception {
        String[] tables = {"transactions", "transaction_installments", "credit_card_invoices", "ledger_postings", "idempotency_keys"};
        long[] before = new long[tables.length];
        for (int i = 0; i < tables.length; i++) {
            before[i] = count(tables[i]);
        }

        JsonNode view = body(mockMvc.perform(post("/v1/cards/" + card + "/purchase-preview", preview("1200.00", 6, "2026-10-10")))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.total.amount").value("1200.00")).andExpect(jsonPath("$.availableLimitAfter.amount").value("8800.00"))
                .andExpect(jsonPath("$.overLimit").value(false)).andExpect(jsonPath("$.warnings.length()").value(0))
                .andExpect(jsonPath("$.installments.length()").value(6)).andExpect(jsonPath("$.installments[0].invoice.status").value("OPEN"))
                .andExpect(jsonPath("$.installments[1].invoice.status").value("FUTURE")).andReturn());

        for (int i = 0; i < tables.length; i++) {
            assertThat(count(tables[i])).as(tables[i]).isEqualTo(before[i]);
        }
        JsonNode real = created(purchase("1200.00", 6, "2026-10-10", "Notebook"));
        for (int k = 0; k < 6; k++) {
            JsonNode p = view.get("installments").get(k);
            JsonNode r = real.get("installments").get(k);
            assertThat(p.get("amount").get("amount").asString()).isEqualTo(r.get("amount").get("amount").asString());
            assertThat(p.get("competence").asString()).isEqualTo(r.get("competence").asString());
            assertThat(p.get("lateEntry").asBoolean()).isEqualTo(r.get("lateEntry").asBoolean());
            assertThat(p.get("invoice").get("id").asString()).isEqualTo(r.get("invoiceId").asString());
            assertThat(p.get("invoice").get("referenceMonth").asString()).isEqualTo(r.get("invoiceReferenceMonth").asString());
            assertThat(p.get("invoice").get("dueDate").asString()).isEqualTo(r.get("invoiceDueDate").asString());
        }
        assertThat(cardNow().get("availableLimit").get("amount").asString()).isEqualTo(view.get("availableLimitAfter").get("amount").asString());
    }

    @Test
    @DisplayName("CA4: compra de 12.000,00 passa do limite: a prévia sinaliza OVER_LIMIT e a criação é aceita (S-125), com o cartão em overLimit")
    void deveAceitarAcimaDoLimite() throws Exception {
        mockMvc.perform(post("/v1/cards/" + card + "/purchase-preview", preview("12000.00", 1, "2026-10-10"))).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.overLimit").value(true))
                .andExpect(jsonPath("$.availableLimitAfter.amount").value("-2000.00")).andExpect(jsonPath("$.warnings[0]").value("OVER_LIMIT"));
        created(purchase("12000.00", 1, "2026-10-10", "Carro"));

        JsonNode afterwards = cardNow();
        assertThat(afterwards.get("overLimit").asBoolean()).isTrue();
        assertThat(afterwards.get("availableLimit").get("amount").asString()).isEqualTo("-2000.00");
        assertThat(afterwards.get("usedRatio").asDouble()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("CA4: a prévia de data futura traz FUTURE_DATE; cartão arquivado, de outro usuário ou entrada inválida são recusados")
    void deveValidarAPrevia() throws Exception {
        mockMvc.perform(post("/v1/cards/" + card + "/purchase-preview", preview("100.00", 2, "2026-11-20"))).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.warnings[0]").value("FUTURE_DATE"));
        mockMvc.perform(post("/v1/cards/" + UUID.randomUUID() + "/purchase-preview", preview("100.00", 1, "2026-10-10")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CARD_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/cards/" + card + "/purchase-preview", preview("100.00", 0, "2026-10-10"))).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/cards/" + card + "/purchase-preview", preview("100.00", 37, "2026-10-10"))).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/cards/" + card + "/purchase-preview", "{\"amount\":\"100.00\",\"date\":\"2026-10-10\"}"))
                .andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/cards/" + card + "/purchase-preview", preview("100.005", 1, "2026-10-10"))).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/cards/" + card + "/purchase-preview", preview("0.05", 6, "2026-10-10"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MONEY_INVALID_ALLOCATION")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/cards/" + card + "/archive", "").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk());
        mockMvc.perform(post("/v1/cards/" + card + "/purchase-preview", preview("100.00", 1, "2026-10-10"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_ARCHIVED")).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("CA5: cartão arquivado é 422 CARD_ARCHIVED; cartão ou categoria de outro usuário é 404; categoria de receita é 422 CATEGORY_KIND_MISMATCH; nada é gravado")
    void deveRecusarEntradaInvalida() throws Exception {
        UUID other = UUID.randomUUID();
        UUID foreignCard = UUID.randomUUID();
        UUID foreignCategory = UUID.randomUUID();
        committed(() -> {
            SqlFixtures.insertUser(jdbc, other, "alheio-" + other + "@example.com");
            jdbc.update("INSERT INTO credit_cards (id, user_id, name, limit_cents, currency, status, created_at, updated_at) "
                    + "VALUES (?, ?, 'Alheio', 100000, 'BRL', 'ACTIVE', now(), now())", foreignCard, other);
            jdbc.update("INSERT INTO credit_card_billing_rules (id, user_id, card_id, effective_from, closing_day, due_day, created_at, updated_at) "
                    + "VALUES (gen_random_uuid(), ?, ?, '2026-10', 25, 5, now(), now())", other, foreignCard);
            SqlFixtures.insertCategory(jdbc, Category.create(foreignCategory, other, "Alheia", CategoryKind.EXPENSE));
        });
        createTx(purchase("10.00", 1, "2026-10-10", "x").replace(card, foreignCard.toString())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CARD_NOT_FOUND")).andExpect(ContractValidation.conforms());
        createTx(purchase("10.00", 1, "2026-10-10", "x").replace(tech, foreignCategory.toString())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND")).andExpect(ContractValidation.conforms());
        createTx(purchase("10.00", 1, "2026-10-10", "x").replace(tech, income)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_KIND_MISMATCH")).andExpect(ContractValidation.conforms());
        createTx(purchase("0.05", 6, "2026-10-10", "x")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MONEY_INVALID_ALLOCATION")).andExpect(ContractValidation.conforms());
        createTx(purchase("0.00", 1, "2026-10-10", "x")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_AMOUNT_NOT_POSITIVE")).andExpect(ContractValidation.conforms());
        createTx(purchase("1000000000.00", 1, "2026-10-10", "x")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AMOUNT_ABOVE_LIMIT")).andExpect(ContractValidation.conforms());
        for (String bad : new String[] {"0", "37", "-1"}) {
            createTx(purchase("10.00", 1, "2026-10-10", "x").replace("\"installments\":1", "\"installments\":" + bad))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(ContractValidation.responseOnly());
        }
        createTx(purchase("10.005", 1, "2026-10-10", "x")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        createTx(purchase("10.00", 1, "2026-10-10", "x").replace(",\"cardId\":\"" + card + "\"", "")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        createTx(purchase("10.00", 1, "2026-10-10", "x").replace("}", ",\"version\":0}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_FIELD")).andExpect(ContractValidation.responseOnly());
        createTx(purchase("10.00", 1, "2026-10-10", "x").replace("}", ",\"invoiceId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_FIELD"))
                .andExpect(ContractValidation.responseOnly());
        assertThat(count("transactions")).isZero();
        assertThat(count("credit_card_invoices")).isZero();

        mockMvc.perform(post("/v1/cards/" + card + "/archive", "").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk());
        createTx(purchase("10.00", 1, "2026-10-10", "x")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_ARCHIVED")).andExpect(ContractValidation.conforms());
        assertThat(count("transactions")).isZero();
    }

    @Test
    @DisplayName("CA6: o mesmo Idempotency-Key e o mesmo corpo devolvem o mesmo id (uma compra, 6 parcelas, 6 faturas); corpo diferente é 409 IDEMPOTENCY_KEY_REUSED")
    void deveSerIdempotente() throws Exception {
        String key = UUID.randomUUID().toString();
        String json = purchase("1200.00", 6, "2026-10-10", "Notebook");

        String first = body(mockMvc.perform(post("/v1/transactions", json).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn()).get("id").asString();
        String second = body(mockMvc.perform(post("/v1/transactions", json).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn()).get("id").asString();

        assertThat(second).isEqualTo(first);
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("transaction_installments")).isEqualTo(6);
        assertThat(count("credit_card_invoices")).isEqualTo(6);
        assertThat(count("ledger_postings")).isEqualTo(14);
        mockMvc.perform(post("/v1/transactions", purchase("1200.00", 5, "2026-10-10", "Notebook")).header("Idempotency-Key", key))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED")).andExpect(ContractValidation.conforms());
        assertThat(count("transactions")).isEqualTo(1);
    }

    @Test
    @DisplayName("T51 CA4: fatura CLOSED de 1.283 em aberto, ou só parcelas futuras, é 422 CARD_HAS_PENDING_BALANCE e o cartão não muda; quitado e sem parcelas, arquiva")
    void deveRecusarArquivarCartaoComSaldo() throws Exception {
        created(purchase("1283.00", 1, "2026-10-10", "Mercado"));
        advanceTo(Instant.parse("2026-10-30T15:00:00Z"));

        mockMvc.perform(post("/v1/cards/" + card + "/archive", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_HAS_PENDING_BALANCE")).andExpect(ContractValidation.conforms());
        JsonNode unchanged = cardNow();
        assertThat(unchanged.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(unchanged.get("archivable").asBoolean()).isFalse();
        assertThat(unchanged.get("currentInvoice").get("status").asString()).isEqualTo("OPEN");

        payInvoice("1283.00", LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 30));
        assertThat(cardNow().get("archivable").asBoolean()).isTrue();
        created(purchase("600.00", 3, "2026-10-30", "Cursos"));
        mockMvc.perform(post("/v1/cards/" + card + "/archive", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_HAS_PENDING_BALANCE"));
        assertThat(cardNow().get("committedFuture").get("amount").asString()).isEqualTo("400.00");
    }

    @Test
    @DisplayName("T51 CA4: tudo quitado e nenhuma parcela futura: o cartão arquiva (ARCHIVED) e desarquiva")
    void deveArquivarCartaoQuitado() throws Exception {
        created(purchase("200.00", 1, "2026-10-10", "Mercado"));
        advanceTo(Instant.parse("2026-10-30T15:00:00Z"));
        payInvoice("200.00", LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 30));

        mockMvc.perform(post("/v1/cards/" + card + "/archive", "")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(post("/v1/cards/" + card + "/unarchive", "")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("duas compras simultâneas no mesmo ciclo são aceitas em série: uma linha de fatura, limite consistente")
    void deveSerializarComprasConcorrentesNoCartao() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<Integer>> results = List.of(
                    pool.submit(() -> concurrentPurchase(barrier, "100.00")), pool.submit(() -> concurrentPurchase(barrier, "250.00")));
            assertThat(results.get(0).get()).isEqualTo(201);
            assertThat(results.get(1).get()).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("credit_card_invoices")).isEqualTo(1);
        assertThat(count("transactions")).isEqualTo(2);
        JsonNode afterwards = cardNow();
        assertThat(afterwards.get("availableLimit").get("amount").asString()).isEqualTo("9650.00");
        assertThat(afterwards.get("currentInvoice").get("total").get("amount").asString()).isEqualTo("350.00");
    }

    private int concurrentPurchase(CyclicBarrier barrier, String amount) throws Exception {
        barrier.await();
        return createTx(purchase(amount, 1, "2026-10-10", "Concorrente " + amount)).andReturn().getResponse().getStatus();
    }
}
