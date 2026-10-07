package com.moneylog.infra.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.FixedClockConfig;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.SqlFixtures;

/**
 * T25: contas (criar, consultar, editar, arquivar), saldo pelo razão e flags. Cada teste usa um usuário novo, então as
 * listas e os saldos não interferem entre si. "Hoje" é 10/10/2026 (relógio fixo).
 */
@IntegrationTest
@Import(FixedClockConfig.class)
@DisplayName("Contas (T25)")
class AccountsIT {

    private static final String OPENING_20K = "{\"name\":\"Nubank\",\"type\":\"CHECKING\",\"institution\":\"Nubank\","
            + "\"openingBalance\":{\"amount\":\"20000.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}";

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
    private LedgerWriteGateway ledger;

    private UUID userId;
    private String bearer;

    @BeforeEach
    void novoUsuario() {
        userId = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, userId, "conta-" + userId + "@moneylog.com"));
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

    private MockHttpServletRequestBuilder put(String url, String json) {
        return MockMvcRequestBuilders.put(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String accountBody(String name, String type, String balance, String date) {
        return "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"openingBalance\":{\"amount\":\"" + balance
                + "\",\"currency\":\"BRL\"},\"openingDate\":\"" + date + "\"}";
    }

    private JsonNode createAccount(String body) throws Exception {
        return json(mockMvc.perform(post("/v1/accounts", body)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
    }

    /** Grava, por SQL e pelo gateway do razão, uma movimentação de domínio já ativa (o POST de movimentações é de T26a). */
    private void insertTransaction(Transaction transaction) {
        committed(() -> {
            SqlFixtures.insertTransactionRow(jdbc, transaction);
            ledger.append(userId, transaction.entries());
        });
    }

    private Account domainAccount(JsonNode account) {
        return Account.restore(UUID.fromString(account.get("id").asString()), userId, account.get("name").asString(),
                AccountType.valueOf(account.get("type").asString()), null, Money.of(account.get("openingBalance").get("amount").asString()),
                LocalDate.parse(account.get("openingDate").asString()), com.moneylog.core.account.domain.AccountStatus.ACTIVE,
                account.get("version").asLong());
    }

    private Category insertCategory(CategoryKind kind) {
        Category category = Category.create(UUID.randomUUID(), userId, "Categoria " + kind, kind);
        committed(() -> SqlFixtures.insertCategory(jdbc, category));
        return category;
    }

    private Transaction incomeOn(Account account, LocalDate date, String amount) {
        return AccountTransactions.income(TransactionHeader.of(UUID.randomUUID(), userId, date, "Receita"), Money.of(amount), account,
                insertCategory(CategoryKind.INCOME), PaymentMethod.TRANSFER);
    }

    // ---------------------------------------------------------------- CA1, CA2, CA7

    @Test
    @DisplayName("CA1: POST cria a conta com saldo inicial como par de lançamentos ACCOUNT_OPENING (+20.000 / EQUITY −20.000)")
    void deveCriarComSaldoInicial() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/accounts", OPENING_20K)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.liquidity").value("CASH")).andExpect(jsonPath("$.currentBalance.amount").value("20000.00"))
                .andExpect(jsonPath("$.openingEditable").value(true)).andExpect(jsonPath("$.archivable").value(false))
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.version").value(0)).andReturn();
        String id = json(result).get("id").asString();

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/accounts/" + id);
        List<Map<String, Object>> postings = jdbc.queryForList(
                "SELECT book_type, book_id, amount_cents, source_type, active FROM ledger_postings WHERE source_id = ? ORDER BY amount_cents",
                UUID.fromString(id));
        assertThat(postings).hasSize(2);
        assertThat(postings.get(0)).containsEntry("book_type", "EQUITY").containsEntry("amount_cents", -2_000_000L)
                .containsEntry("source_type", "ACCOUNT_OPENING").containsEntry("active", true);
        assertThat(postings.get(1)).containsEntry("book_type", "ACCOUNT").containsEntry("book_id", UUID.fromString(id))
                .containsEntry("amount_cents", 2_000_000L);
    }

    @Test
    @DisplayName("CA2: com data de abertura futura (20/10) o saldo de hoje (10/10) é 0,00")
    void deveTerSaldoZeroAteADataDeAbertura() throws Exception {
        mockMvc.perform(post("/v1/accounts", accountBody("Futura", "CHECKING", "100.00", "2026-10-20"))).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.currentBalance.amount").value("0.00"))
                .andExpect(jsonPath("$.openingBalance.amount").value("100.00")).andExpect(jsonPath("$.archivable").value(false));
    }

    @Test
    @DisplayName("CA7: saldo negativo é aceito e o saldo atual vem negativo, sem erro (S-5)")
    void deveAceitarSaldoNegativo() throws Exception {
        mockMvc.perform(post("/v1/accounts", accountBody("Cheque especial", "CHECKING", "-1500.00", "2026-10-01")))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.currentBalance.amount").value("-1500.00"));
    }

    @Test
    @DisplayName("corpo inválido: sem nome, tipo desconhecido, moeda diferente de BRL e valor acima do teto")
    void deveValidarOCorpo() throws Exception {
        mockMvc.perform(post("/v1/accounts", "{\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/accounts", accountBody("X", "CARTAO", "1.00", "2026-10-01"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_FORMAT")).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/accounts", accountBody("X", "CHECKING", "1.00", "2026-10-01").replace("BRL", "USD")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ACCOUNT_CURRENCY_NOT_SUPPORTED"))
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/accounts", accountBody("X", "CHECKING", "1000000000.00", "2026-10-01")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("AMOUNT_ABOVE_LIMIT"))
                .andExpect(ContractValidation.conforms());
        assertThat(jdbc.queryForObject("select count(*) from accounts where user_id = ?", Integer.class, userId)).isZero();
    }

    // ---------------------------------------------------------------- CA3

    @Test
    @DisplayName("CA3: sem movimentação o PUT troca o saldo inicial (par antigo inativo, par novo ativo); com movimentação, 422 ACCOUNT_OPENING_LOCKED")
    void deveCorrigirOSaldoInicialSoSemMovimentacao() throws Exception {
        JsonNode created = createAccount(OPENING_20K);
        String id = created.get("id").asString();

        mockMvc.perform(put("/v1/accounts/" + id, "{\"name\":\"Nubank\",\"openingBalance\":{\"amount\":\"15000.00\",\"currency\":\"BRL\"},\"version\":0}"))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.currentBalance.amount").value("15000.00")).andExpect(jsonPath("$.openingBalance.amount").value("15000.00"))
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.openingEditable").value(true));

        assertThat(jdbc.queryForObject("select count(*) from ledger_postings where source_id = ? and not active and deactivation_reason = 'OPENING_CORRECTED'",
                Integer.class, UUID.fromString(id))).isEqualTo(2);
        assertThat(jdbc.queryForObject("select sum(amount_cents) from ledger_postings where source_id = ? and book_type = 'ACCOUNT' and active",
                Long.class, UUID.fromString(id))).isEqualTo(1_500_000L);

        insertTransaction(incomeOn(domainAccount(json(mockMvc.perform(get("/v1/accounts/" + id)).andReturn())), LocalDate.of(2026, 10, 5), "10.00"));

        mockMvc.perform(put("/v1/accounts/" + id, "{\"name\":\"Nubank\",\"openingBalance\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},\"version\":1}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ACCOUNT_OPENING_LOCKED"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(put("/v1/accounts/" + id, "{\"name\":\"Nubank Roxinho\",\"institution\":\"NuPay\",\"version\":1}"))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.name").value("Nubank Roxinho"))
                .andExpect(jsonPath("$.institution").value("NuPay")).andExpect(jsonPath("$.openingEditable").value(false))
                .andExpect(jsonPath("$.currentBalance.amount").value("15010.00"));
    }

    @Test
    @DisplayName("PUT com versão antiga é 409 VERSION_CONFLICT")
    void deveRecusarVersaoAntiga() throws Exception {
        String id = createAccount(OPENING_20K).get("id").asString();
        mockMvc.perform(put("/v1/accounts/" + id, "{\"name\":\"Novo nome\",\"version\":0}")).andExpect(status().isOk());

        mockMvc.perform(put("/v1/accounts/" + id, "{\"name\":\"Outro\",\"version\":0}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT")).andExpect(ContractValidation.conforms());
        assertThat(jdbc.queryForObject("select name from accounts where id = ?", String.class, UUID.fromString(id))).isEqualTo("Novo nome");
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4: saldo zero arquiva (ARCHIVED) e desarquiva (ACTIVE); com saldo ≠ 0 é 422 e a conta não muda")
    void deveArquivarSoComSaldoZero() throws Exception {
        String empty = createAccount(accountBody("XP", "CHECKING", "0.00", "2026-10-01")).get("id").asString();
        String funded = createAccount(OPENING_20K).get("id").asString();

        mockMvc.perform(get("/v1/accounts/" + empty)).andExpect(jsonPath("$.archivable").value(true));
        mockMvc.perform(post("/v1/accounts/" + empty + "/archive", "")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.status").value("ARCHIVED")).andExpect(jsonPath("$.archivable").value(false));
        mockMvc.perform(post("/v1/accounts/" + empty + "/archive", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_ARCHIVED")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/accounts/" + empty + "/unarchive", "")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/v1/accounts/" + empty + "/unarchive", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ARCHIVED"));

        mockMvc.perform(post("/v1/accounts/" + funded + "/archive", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_WITH_BALANCE_CANNOT_BE_ARCHIVED")).andExpect(ContractValidation.conforms());
        assertThat(jdbc.queryForObject("select status from accounts where id = ?", String.class, UUID.fromString(funded))).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("CA4 (S-92): saldo zero hoje mas movimentação futura, ou saldo inicial com data futura: archivable false e 422 de saldo futuro")
    void deveContarSaldoEmTodasAsDatas() throws Exception {
        JsonNode account = createAccount(accountBody("Com futuro", "CHECKING", "0.00", "2026-10-01"));
        insertTransaction(incomeOn(domainAccount(account), LocalDate.of(2026, 10, 20), "50.00"));
        String futureOpening = createAccount(accountBody("Abre depois", "SAVINGS", "100.00", "2026-10-20")).get("id").asString();

        for (String id : List.of(account.get("id").asString(), futureOpening)) {
            mockMvc.perform(get("/v1/accounts/" + id)).andExpect(jsonPath("$.currentBalance.amount").value("0.00"))
                    .andExpect(jsonPath("$.archivable").value(false));
            mockMvc.perform(post("/v1/accounts/" + id + "/archive", "")).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_WITH_FUTURE_BALANCE_CANNOT_BE_ARCHIVED")).andExpect(ContractValidation.conforms());
        }
    }

    // ---------------------------------------------------------------- CA5, CA6

    @Test
    @DisplayName("CA5: lista filtra por status, ordena por nome e pagina com hasNext correto (pageSize=1 com 2 contas)")
    void deveListarComFiltroOrdemEPaginacao() throws Exception {
        createAccount(accountBody("Zeta", "WALLET", "0.00", "2026-10-01"));
        String alpha = createAccount(accountBody("alfa", "CHECKING", "0.00", "2026-10-01")).get("id").asString();
        mockMvc.perform(post("/v1/accounts/" + alpha + "/archive", "")).andExpect(status().isOk());

        JsonNode all = json(mockMvc.perform(get("/v1/accounts")).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(all.get("items").valueStream().map(a -> a.get("name").asString())).containsExactly("alfa", "Zeta");
        assertThat(all.get("hasNext").asBoolean()).isFalse();

        JsonNode active = json(mockMvc.perform(get("/v1/accounts?status=ACTIVE")).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(active.get("items").valueStream().map(a -> a.get("name").asString())).containsExactly("Zeta");
        JsonNode archived = json(mockMvc.perform(get("/v1/accounts?status=ARCHIVED")).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(archived.get("items").valueStream().map(a -> a.get("name").asString())).containsExactly("alfa");

        JsonNode page1 = json(mockMvc.perform(get("/v1/accounts?page=1&pageSize=1")).andExpect(ContractValidation.conforms()).andReturn());
        JsonNode page2 = json(mockMvc.perform(get("/v1/accounts?page=2&pageSize=1")).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(page1.get("hasNext").asBoolean()).isTrue();
        assertThat(page1.get("items").valueStream().map(a -> a.get("name").asString())).containsExactly("alfa");
        assertThat(page2.get("hasNext").asBoolean()).isFalse();
        assertThat(page2.get("items").valueStream().map(a -> a.get("name").asString())).containsExactly("Zeta");
        mockMvc.perform(get("/v1/accounts?status=NOPE")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(get("/v1/accounts?pageSize=51")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
    }

    @Test
    @DisplayName("CA6: conta de outro usuário é 404 ACCOUNT_NOT_FOUND em GET, PUT, archive e unarchive, e não aparece na lista")
    void deveIsolarPorUsuario() throws Exception {
        UUID other = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        committed(() -> {
            SqlFixtures.insertUser(jdbc, other, "outra-" + other + "@moneylog.com");
            SqlFixtures.insertAccount(jdbc, Account.open(accountId, other, "Alheia", AccountType.CHECKING, null, Money.of("1.00"),
                    LocalDate.of(2026, 10, 1)));
        });

        mockMvc.perform(get("/v1/accounts/" + accountId)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(put("/v1/accounts/" + accountId, "{\"name\":\"X\",\"version\":0}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/accounts/" + accountId + "/archive", "")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/accounts/" + accountId + "/unarchive", "")).andExpect(status().isNotFound())
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(get("/v1/accounts")).andExpect(jsonPath("$.items").isEmpty());
        assertThat(jdbc.queryForObject("select name from accounts where id = ?", String.class, accountId)).isEqualTo("Alheia");
    }

    @Test
    @DisplayName("GET /v1/accounts/{id} devolve a conta com saldo e flags")
    void deveConsultarUmaConta() throws Exception {
        String id = createAccount(OPENING_20K).get("id").asString();

        mockMvc.perform(get("/v1/accounts/" + id)).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.type").value("CHECKING"))
                .andExpect(jsonPath("$.institution").value("Nubank")).andExpect(jsonPath("$.currentBalance.currency").value("BRL"));
    }
}
