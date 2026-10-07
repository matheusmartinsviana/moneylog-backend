package com.moneylog.infra.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

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

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.FixedClockConfig;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.SqlFixtures;

/**
 * T26a: criar e consultar movimentações. Cenário: Nubank (CHECKING 20.000,00), XP (CHECKING 0), XP Invest (INVESTMENT 0),
 * relógio em 10/10/2026. Cada teste usa um usuário novo.
 */
@IntegrationTest
@Import(FixedClockConfig.class)
@DisplayName("Movimentações: criar e consultar (T26a)")
class TransactionsIT {

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

    private UUID userId;
    private String bearer;
    private String nubank;
    private String xp;
    private String xpInvest;
    private String food;
    private String salary;

    @BeforeEach
    void cenario() throws Exception {
        userId = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, userId, "mov-" + userId + "@moneylog.com"));
        bearer = auth.bearer(userId);
        nubank = account("Nubank", "CHECKING", "20000.00");
        xp = account("XP", "CHECKING", "0.00");
        xpInvest = account("XP Invest", "INVESTMENT", "0.00");
        food = category("Alimentação", CategoryKind.EXPENSE);
        salary = category("Salário", CategoryKind.INCOME);
    }

    // ---------------------------------------------------------------- helpers

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

    private String account(String name, String type, String balance) throws Exception {
        return body(mockMvc.perform(post("/v1/accounts", "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"openingBalance\":{\"amount\":\""
                + balance + "\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private String category(String name, CategoryKind kind) {
        Category category = Category.create(UUID.randomUUID(), userId, name, kind);
        committed(() -> SqlFixtures.insertCategory(jdbc, category));
        return category.id().toString();
    }

    private String balance(String accountId) throws Exception {
        return body(mockMvc.perform(get("/v1/accounts/" + accountId)).andReturn()).get("currentBalance").get("amount").asString();
    }

    private String expense(String amount) {
        return "{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"2026-10-10\",\"description\":\"Restaurante\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}";
    }

    private String transfer(String from, String to, String amount) {
        return "{\"type\":\"TRANSFER\",\"amount\":\"" + amount + "\",\"date\":\"2026-10-10\",\"description\":\"Transferência\",\"fromAccountId\":\""
                + from + "\",\"toAccountId\":\"" + to + "\"}";
    }

    private String investment(String direction, String amount) {
        return "{\"type\":\"INVESTMENT\",\"amount\":\"" + amount + "\",\"date\":\"2026-10-10\",\"description\":\"CDB\",\"cashAccountId\":\"" + nubank
                + "\",\"investmentAccountId\":\"" + xpInvest + "\",\"direction\":\"" + direction + "\"}";
    }

    private JsonNode created(String json) throws Exception {
        return body(createTx(json).andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
    }

    // ---------------------------------------------------------------- CA1, CA8, CA9

    @Test
    @DisplayName("CA1/CA9: despesa Pix de 100,00 devolve 201 + Location + impacto e flags; a Nubank passa a 19.900,00 pelo mesmo carregamento")
    void deveCriarDespesa() throws Exception {
        MvcResult result = createTx(expense("100.00")).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.type").value("EXPENSE")).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.amount.amount").value("100.00")).andExpect(jsonPath("$.method").value("PIX"))
                .andExpect(jsonPath("$.account.id").value(nubank)).andExpect(jsonPath("$.account.name").value("Nubank"))
                .andExpect(jsonPath("$.category.name").value("Alimentação")).andExpect(jsonPath("$.counterpartAccount").doesNotExist())
                .andExpect(jsonPath("$.impact.cash.amount").value("-100.00")).andExpect(jsonPath("$.impact.netWorth.amount").value("-100.00"))
                .andExpect(jsonPath("$.impact.commitment.amount").value("0.00"))
                .andExpect(jsonPath("$.impact.consumption[0].competence").value("2026-10"))
                .andExpect(jsonPath("$.impact.consumption[0].amount.amount").value("100.00"))
                .andExpect(jsonPath("$.voidable").value(true)).andExpect(jsonPath("$.voidBlockedBy").doesNotExist())
                .andExpect(jsonPath("$.editable").value(true)).andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.voidReason").doesNotExist()).andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.installments").isEmpty()).andReturn();
        String id = body(result).get("id").asString();

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/transactions/" + id);
        assertThat(balance(nubank)).isEqualTo("19900.00");
        assertThat(jdbc.queryForObject("select count(*) from ledger_postings where transaction_id = ? and active", Integer.class, UUID.fromString(id))).isEqualTo(2);
    }

    @Test
    @DisplayName("CA8: GET devolve o mesmo recurso do POST; de outro usuário é 404 TRANSACTION_NOT_FOUND")
    void deveConsultar() throws Exception {
        JsonNode created = created(expense("100.00"));

        MvcResult fetched = mockMvc.perform(get("/v1/transactions/" + created.get("id").asString())).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn();
        assertThat(body(fetched)).isEqualTo(created);

        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com"));
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/transactions/" + created.get("id").asString()).header(HttpHeaders.AUTHORIZATION, auth.bearer(other)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(get("/v1/transactions/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("CA8 (S-88): transferência cuja conta foi arquivada depois vem com editable e voidable falsos e voidBlockedBy ACCOUNT_ARCHIVED")
    void deveBloquearFlagsComContaArquivada() throws Exception {
        JsonNode first = created(transfer(nubank, xp, "1000.00"));
        created(transfer(xp, nubank, "1000.00"));
        mockMvc.perform(post("/v1/accounts/" + xp + "/archive", "")).andExpect(status().isOk());

        mockMvc.perform(get("/v1/transactions/" + first.get("id").asString())).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.editable").value(false)).andExpect(jsonPath("$.voidable").value(false))
                .andExpect(jsonPath("$.voidBlockedBy").value("ACCOUNT_ARCHIVED"));
    }

    // ---------------------------------------------------------------- CA2, CA3

    @Test
    @DisplayName("CA2: transferência Nubank → XP: caixa, consumo e patrimônio zerados; saldos 18.900,00 e 1.000,00")
    void deveCriarTransferencia() throws Exception {
        createTx(transfer(nubank, xp, "1000.00")).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.impact.cash.amount").value("0.00")).andExpect(jsonPath("$.impact.consumption").isEmpty())
                .andExpect(jsonPath("$.impact.netWorth.amount").value("0.00")).andExpect(jsonPath("$.counterpartAccount.id").value(xp))
                .andExpect(jsonPath("$.category").doesNotExist());

        assertThat(balance(nubank)).isEqualTo("19000.00");
        assertThat(balance(xp)).isEqualTo("1000.00");
    }

    @Test
    @DisplayName("CA2: Nubank → XP Invest é 422 TRANSFER_ACROSS_LIQUIDITY e origem = destino é 422 TRANSFER_SAME_ACCOUNT")
    void deveRecusarTransferenciaInvalida() throws Exception {
        createTx(transfer(nubank, xpInvest, "1.00")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSFER_ACROSS_LIQUIDITY")).andExpect(ContractValidation.conforms());
        createTx(transfer(nubank, nubank, "1.00")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSFER_SAME_ACCOUNT")).andExpect(ContractValidation.conforms());
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    @DisplayName("CA3: aplicação de 10.000,00 não toca consumo nem patrimônio; resgate inverte os saldos")
    void deveCriarAplicacaoEResgate() throws Exception {
        createTx(investment("CONTRIBUTION", "10000.00")).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.direction").value("CONTRIBUTION")).andExpect(jsonPath("$.impact.cash.amount").value("-10000.00"))
                .andExpect(jsonPath("$.impact.consumption").isEmpty()).andExpect(jsonPath("$.impact.netWorth.amount").value("0.00"))
                .andExpect(jsonPath("$.account.id").value(nubank)).andExpect(jsonPath("$.counterpartAccount.id").value(xpInvest));
        assertThat(balance(nubank)).isEqualTo("10000.00");
        assertThat(balance(xpInvest)).isEqualTo("10000.00");

        createTx(investment("REDEMPTION", "4000.00")).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.impact.cash.amount").value("4000.00"));
        assertThat(balance(nubank)).isEqualTo("14000.00");
        assertThat(balance(xpInvest)).isEqualTo("6000.00");
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4: receita de 3.000,00 em Salário; categoria de despesa em receita é 422 CATEGORY_KIND_MISMATCH")
    void deveCriarReceita() throws Exception {
        createTx("{\"type\":\"INCOME\",\"amount\":\"3000.00\",\"date\":\"2026-10-05\",\"description\":\"Salário de outubro\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + salary + "\",\"method\":\"TRANSFER\"}").andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.impact.cash.amount").value("3000.00"))
                .andExpect(jsonPath("$.impact.consumption").isEmpty());
        createTx("{\"type\":\"INCOME\",\"amount\":\"3000.00\",\"date\":\"2026-10-05\",\"description\":\"X\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"method\":\"TRANSFER\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_KIND_MISMATCH")).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("CA4: reembolso de 50,00 ligado à despesa gera consumo −50,00; o segundo, de 60,00, é 422 REFUND_EXCEEDS_ORIGINAL")
    void deveCriarReembolso() throws Exception {
        JsonNode expense = created(expense("100.00"));
        String refund = "{\"type\":\"REFUND\",\"amount\":\"%s\",\"date\":\"2026-10-10\",\"description\":\"Estorno do restaurante\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + food + "\",\"refundOfTransactionId\":\"" + expense.get("id").asString() + "\"}";

        createTx(refund.formatted("50.00")).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.impact.consumption[0].amount.amount").value("-50.00")).andExpect(jsonPath("$.impact.cash.amount").value("50.00"))
                .andExpect(jsonPath("$.refundOf.id").value(expense.get("id").asString())).andExpect(jsonPath("$.refundOf.description").value("Restaurante"));
        createTx(refund.formatted("60.00")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_ORIGINAL")).andExpect(ContractValidation.conforms());
        assertThat(balance(nubank)).isEqualTo("19950.00");
        mockMvc.perform(get("/v1/transactions/" + expense.get("id").asString())).andExpect(jsonPath("$.voidable").value(false))
                .andExpect(jsonPath("$.voidBlockedBy").value("HAS_ACTIVE_REFUNDS")).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("CA4: ajuste sem motivo é recusado (400); com motivo é aceito e não toca consumo")
    void deveCriarAjuste() throws Exception {
        String base = "{\"type\":\"ADJUSTMENT\",\"amount\":\"25.00\",\"date\":\"2026-10-10\",\"description\":\"Ajuste\",\"accountId\":\"" + nubank
                + "\",\"direction\":\"DECREASE\"%s}";
        createTx(base.formatted(",\"reason\":\" \"")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(ContractValidation.responseOnly());
        createTx(base.formatted(",\"reason\":\"conferido no extrato\"")).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.reason").value("conferido no extrato")).andExpect(jsonPath("$.adjustmentKind").value("CORRECTION"))
                .andExpect(jsonPath("$.direction").value("DECREASE")).andExpect(jsonPath("$.impact.consumption").isEmpty())
                .andExpect(jsonPath("$.impact.netWorth.amount").value("-25.00"));
    }

    // ---------------------------------------------------------------- CA5, CA6, CA7

    @Test
    @DisplayName("CA5: pagamento de fatura (só pelo endpoint da fatura) é 422 TRANSACTION_TYPE_NOT_AVAILABLE e nada é gravado; reembolso com cartão inexistente é 404 CARD_NOT_FOUND (T56); os tipos de dívida (Etapa 4) exigem o corpo completo: 400")
    void deveRecusarTiposIndisponiveis() throws Exception {
        for (String type : new String[] {"INVOICE_PAYMENT"}) {
            createTx("{\"type\":\"" + type + "\"}").andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("TRANSACTION_TYPE_NOT_AVAILABLE")).andExpect(ContractValidation.conforms());
        }
        // Etapa 4: os tipos de dívida têm schema próprio; o corpo mínimo do contrato antigo (só o type) não basta mais: 400
        for (String type : new String[] {"LOAN_GIVEN", "MONEY_RECEIVED", "DEBT_PAYMENT"}) {
            createTx("{\"type\":\"" + type + "\"}").andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        createTx("{\"type\":\"REFUND\",\"amount\":\"5.00\",\"date\":\"2026-10-10\",\"description\":\"No cartão\",\"categoryId\":\"" + food
                + "\",\"cardId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CARD_NOT_FOUND")).andExpect(ContractValidation.conforms());
        // conta e cartão juntos (ou nenhum dos dois) não passa nem pelo contrato: 400, não 422
        createTx("{\"type\":\"REFUND\",\"amount\":\"5.00\",\"date\":\"2026-10-10\",\"description\":\"Ambos\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"cardId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(ContractValidation.responseOnly());
        createTx("{\"type\":\"REFUND\",\"amount\":\"5.00\",\"date\":\"2026-10-10\",\"description\":\"Nenhum\",\"categoryId\":\"" + food
                + "\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(ContractValidation.responseOnly());
        createTx("{\"type\":\"NOPE\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(ContractValidation.responseOnly());
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    @DisplayName("CA6: sem o header Idempotency-Key é 400 VALIDATION_FAILED; valor com 3 casas ou número também")
    void deveExigirIdempotencyKeEValidarValor() throws Exception {
        mockMvc.perform(post("/v1/transactions", expense("100.00"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"))
                .andExpect(ContractValidation.responseOnly());
        createTx(expense("10.005")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(ContractValidation.responseOnly());
        createTx(expense("100.00").replace("\"100.00\"", "100.00")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        createTx(expense("0.00")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_AMOUNT_NOT_POSITIVE"))
                .andExpect(ContractValidation.conforms());
        createTx(expense("1000000000.00")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("AMOUNT_ABOVE_LIMIT"))
                .andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("CA7: conta arquivada é 422 ACCOUNT_ARCHIVED, categoria arquivada é 422 CATEGORY_ARCHIVED; conta ou categoria de outro usuário é 404")
    void deveRecusarReferenciasInvalidas() throws Exception {
        mockMvc.perform(post("/v1/accounts/" + xp + "/archive", "")).andExpect(status().isOk());
        createTx(transfer(nubank, xp, "1.00")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ACCOUNT_ARCHIVED"))
                .andExpect(ContractValidation.conforms());

        String archivedCategory = category("Velha", CategoryKind.EXPENSE);
        mockMvc.perform(post("/v1/categories/" + archivedCategory + "/archive", "")).andExpect(status().isOk());
        createTx(expense("1.00").replace(food, archivedCategory)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_ARCHIVED")).andExpect(ContractValidation.conforms());

        UUID other = UUID.randomUUID();
        UUID otherAccount = UUID.randomUUID();
        committed(() -> {
            SqlFixtures.insertUser(jdbc, other, "alheio-" + other + "@moneylog.com");
            SqlFixtures.insertAccount(jdbc, com.moneylog.core.account.domain.Account.open(otherAccount, other, "Alheia",
                    com.moneylog.core.account.domain.AccountType.CHECKING, null, com.moneylog.core.sk.money.Money.of("1.00"), LocalDate.of(2026, 10, 1)));
        });
        createTx(expense("1.00").replace(nubank, otherAccount.toString())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND")).andExpect(ContractValidation.conforms());
        createTx(expense("1.00").replace(food, UUID.randomUUID().toString())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND")).andExpect(ContractValidation.conforms());
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    @DisplayName("CA9: o saldo da conta lê as movimentações pelo mesmo carregamento (despesa futura fica fora do saldo de hoje)")
    void deveContarSoOQueJaAconteceu() throws Exception {
        createTx(expense("100.00").replace("2026-10-10", "2026-10-20")).andExpect(status().isCreated());

        assertThat(balance(nubank)).isEqualTo("20000.00");
        mockMvc.perform(get("/v1/accounts/" + nubank)).andExpect(jsonPath("$.openingEditable").value(false))
                .andExpect(jsonPath("$.archivable").value(false));
    }

    @Test
    @DisplayName("sem token é 401 UNAUTHENTICATED")
    void deveExigirToken() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/transactions").contentType(MediaType.APPLICATION_JSON).content(expense("1.00")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
