package com.moneylog.infra.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.FixedClockConfig;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@Import(FixedClockConfig.class)
@DisplayName("Edição com revisões e estorno (T27)")
class TransactionEditVoidIT {

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
    private String food;
    private String leisure;

    @BeforeEach
    void cenario() throws Exception {
        userId = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, userId, "edit-" + userId + "@example.com"));
        bearer = auth.bearer(userId);
        nubank = account("Nubank", "20000.00");
        xp = account("XP", "0.00");
        food = category("Alimentação");
        leisure = category("Lazer");
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

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String account(String name, String balance) throws Exception {
        return body(mockMvc.perform(post("/v1/accounts", "{\"name\":\"" + name + "\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\""
                + balance + "\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private String category(String name) {
        Category category = Category.create(UUID.randomUUID(), userId, name, CategoryKind.EXPENSE);
        committed(() -> SqlFixtures.insertCategory(jdbc, category));
        return category.id().toString();
    }

    private String balance(String accountId) throws Exception {
        return body(mockMvc.perform(get("/v1/accounts/" + accountId)).andReturn()).get("currentBalance").get("amount").asString();
    }

    private JsonNode create(String json) throws Exception {
        return body(mockMvc.perform(post("/v1/transactions", json).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
    }

    private String expenseJson(String type, String amount, String categoryId, String extra) {
        return "{\"type\":\"" + type + "\",\"amount\":\"" + amount + "\",\"date\":\"2026-10-10\",\"description\":\"Restaurante\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + categoryId + "\",\"method\":\"PIX\"" + extra + "}";
    }

    private JsonNode createExpense(String amount) throws Exception {
        return create(expenseJson("EXPENSE", amount, food, ""));
    }

    private String refundJson(String amount, String originalId, String categoryId, String extra) {
        return "{\"type\":\"REFUND\",\"amount\":\"" + amount + "\",\"date\":\"2026-10-10\",\"description\":\"Reembolso\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + categoryId + "\",\"refundOfTransactionId\":\"" + originalId + "\"" + extra + "}";
    }

    private JsonNode createRefund(String amount, String originalId) throws Exception {
        return create(refundJson(amount, originalId, food, ""));
    }

    private ResultActions edit(String id, String json) throws Exception {
        return mockMvc.perform(put("/v1/transactions/" + id, json));
    }

    private ResultActions voidIt(String id, String reason) throws Exception {
        return mockMvc.perform(post("/v1/transactions/" + id + "/void", "{\"reason\":\"" + reason + "\"}"));
    }

    private JsonNode fetch(String id) throws Exception {
        return body(mockMvc.perform(get("/v1/transactions/" + id)).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int activePostings(String id) {
        return count("select count(*) from ledger_postings where transaction_id = ? and active", UUID.fromString(id));
    }

    private int revisions(String id) {
        return count("select count(*) from transaction_revisions where transaction_id = ?", UUID.fromString(id));
    }

    @Test
    @DisplayName("CA1: PUT de 100,00 Alimentação para 120,00 Lazer com version 0: 200, version 1, consumo 120,00 em Lazer; postings antigos inativos (EDIT), novos ativos; revisão 1 com o estado anterior; Nubank 19.880,00")
    void deveEditarComRevisao() throws Exception {
        JsonNode created = createExpense("100.00");
        String id = created.get("id").asString();

        MvcResult result = edit(id, expenseJson("EXPENSE", "120.00", leisure, ",\"version\":0")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.amount.amount").value("120.00")).andExpect(jsonPath("$.category.name").value("Lazer"))
                .andExpect(jsonPath("$.impact.consumption[0].amount.amount").value("120.00"))
                .andExpect(jsonPath("$.impact.cash.amount").value("-120.00")).andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.voidable").value(true)).andReturn();

        assertThat(body(result).get("createdAt")).isEqualTo(created.get("createdAt"));
        assertThat(balance(nubank)).isEqualTo("19880.00");
        assertThat(count("select count(*) from ledger_postings where transaction_id = ? and not active and deactivation_reason = 'EDIT'", UUID.fromString(id))).isEqualTo(2);
        assertThat(activePostings(id)).isEqualTo(2);
        assertThat(count("select count(*) from ledger_postings where transaction_id = ? and active and book_id = ?", UUID.fromString(id), UUID.fromString(leisure))).isEqualTo(1);
        assertThat(revisions(id)).isEqualTo(1);
        JsonNode snapshot = objectMapper.readTree(jdbc.queryForObject("select snapshot::text from transaction_revisions where transaction_id = ? and revision = 1", String.class, UUID.fromString(id)));
        assertThat(snapshot.get("amount").asString()).isEqualTo("100.00");
        assertThat(snapshot.get("categoryId").asString()).isEqualTo(food);
        assertThat(snapshot.get("version").asLong()).isZero();
        assertThat(jdbc.queryForObject("select user_id from transaction_revisions where transaction_id = ?", UUID.class, UUID.fromString(id))).isEqualTo(userId);
        assertThat(fetch(id)).isEqualTo(body(result));

        edit(id, expenseJson("EXPENSE", "130.00", leisure, ",\"version\":1")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                .andExpect(ContractValidation.conforms());
        assertThat(revisions(id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select snapshot->>'amount' from transaction_revisions where transaction_id = ? and revision = 2", String.class, UUID.fromString(id))).isEqualTo("120.00");
        assertThat(balance(nubank)).isEqualTo("19870.00");
    }

    @Test
    @DisplayName("CA1: editar sem mudar nenhum campo ainda cria a versão e a revisão (a edição sempre registra)")
    void deveRegistrarEdicaoSemMudanca() throws Exception {
        String id = createExpense("100.00").get("id").asString();

        edit(id, expenseJson("EXPENSE", "100.00", food, ",\"version\":0")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(ContractValidation.conforms());
        assertThat(revisions(id)).isEqualTo(1);
        assertThat(balance(nubank)).isEqualTo("19900.00");
    }

    @Test
    @DisplayName("CA2: version antiga é 409 VERSION_CONFLICT e nada muda; type diferente é 422 TRANSACTION_NOT_EDITABLE; version ausente é 400")
    void deveRecusarVersaoAntigaETipoDiferente() throws Exception {
        String id = createExpense("100.00").get("id").asString();
        edit(id, expenseJson("EXPENSE", "120.00", food, ",\"version\":0")).andExpect(status().isOk());

        edit(id, expenseJson("EXPENSE", "999.00", food, ",\"version\":0")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT")).andExpect(ContractValidation.conforms());
        assertThat(fetch(id).get("amount").get("amount").asString()).isEqualTo("120.00");
        assertThat(revisions(id)).isEqualTo(1);

        edit(id, expenseJson("INCOME", "120.00", food, ",\"version\":1")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_EDITABLE")).andExpect(ContractValidation.conforms());
        edit(id, "{\"type\":\"INVOICE_PAYMENT\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_EDITABLE")).andExpect(ContractValidation.conforms());
        edit(id, expenseJson("EXPENSE", "120.00", food, "")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("version")).andExpect(ContractValidation.responseOnly());
        edit(UUID.randomUUID().toString(), expenseJson("EXPENSE", "1.00", food, ",\"version\":0")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND")).andExpect(ContractValidation.conforms());
        assertThat(fetch(id).get("version").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("duas edições concorrentes com a mesma version: uma é 200 e a outra 409; uma só revisão; versão 1")
    void deveSerializarEdicoesConcorrentes() throws Exception {
        String id = createExpense("100.00").get("id").asString();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            Callable<MvcResult> a = () -> {
                ready.countDown();
                go.await();
                return edit(id, expenseJson("EXPENSE", "110.00", food, ",\"version\":0")).andReturn();
            };
            Callable<MvcResult> b = () -> {
                ready.countDown();
                go.await();
                return edit(id, expenseJson("EXPENSE", "120.00", food, ",\"version\":0")).andReturn();
            };
            Future<MvcResult> fa = pool.submit(a);
            Future<MvcResult> fb = pool.submit(b);
            ready.await();
            go.countDown();
            statuses.add(fa.get().getResponse().getStatus());
            statuses.add(fb.get().getResponse().getStatus());
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(revisions(id)).isEqualTo(1);
        assertThat(fetch(id).get("version").asLong()).isEqualTo(1);
        assertThat(activePostings(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("CA3: despesa com reembolso ativo de 50,00: trocar a categoria é 422 REFUND_CATEGORY_LOCKED; 40,00 é 422 REFUND_EXCEEDS_ORIGINAL; 50,00 e 80,00 são 200; nas recusas nada muda")
    void deveAplicarOInvarianteDoReembolso() throws Exception {
        String id = createExpense("100.00").get("id").asString();
        createRefund("50.00", id);

        edit(id, expenseJson("EXPENSE", "100.00", leisure, ",\"version\":0")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_CATEGORY_LOCKED")).andExpect(ContractValidation.conforms());
        edit(id, expenseJson("EXPENSE", "40.00", food, ",\"version\":0")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_ORIGINAL")).andExpect(ContractValidation.conforms());
        assertThat(fetch(id).get("version").asLong()).isZero();
        assertThat(revisions(id)).isZero();
        assertThat(activePostings(id)).isEqualTo(2);
        assertThat(count("select count(*) from ledger_postings where transaction_id = ? and not active", UUID.fromString(id))).isZero();
        assertThat(balance(nubank)).isEqualTo("19950.00");

        edit(id, expenseJson("EXPENSE", "50.00", food, ",\"version\":0")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.voidBlockedBy").value("HAS_ACTIVE_REFUNDS")).andExpect(jsonPath("$.editable").value(true));
        edit(id, expenseJson("EXPENSE", "80.00", food, ",\"version\":1")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        assertThat(balance(nubank)).isEqualTo("19970.00");
    }

    @Test
    @DisplayName("CA4: dois reembolsos de 30,00 (R1) e 20,00 (R2): R1 para 80,00 é 200 (teto 100 − 20); 80,01 é 422 REFUND_EXCEEDS_ORIGINAL; categoria diferente é 422 REFUND_INVALID_ORIGINAL")
    void deveRevalidarOReembolsoSemContarASiMesmo() throws Exception {
        String expense = createExpense("100.00").get("id").asString();
        String r1 = createRefund("30.00", expense).get("id").asString();
        createRefund("20.00", expense);

        edit(r1, refundJson("80.01", expense, food, ",\"version\":0")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_ORIGINAL")).andExpect(ContractValidation.conforms());
        edit(r1, refundJson("10.00", expense, leisure, ",\"version\":0")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_INVALID_ORIGINAL")).andExpect(ContractValidation.conforms());
        assertThat(fetch(r1).get("version").asLong()).isZero();

        edit(r1, refundJson("80.00", expense, food, ",\"version\":0")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.amount.amount").value("80.00")).andExpect(jsonPath("$.refundOf.id").value(expense))
                .andExpect(jsonPath("$.impact.consumption[0].amount.amount").value("-80.00"));
        edit(expense, expenseJson("EXPENSE", "99.00", food, ",\"version\":0")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_ORIGINAL"));
    }

    @Test
    @DisplayName("CA5: POST /void devolve VOIDED com voidedAt e voidReason, flags falsas, postings inativos e o saldo volta; estornar de novo é 422; reason em branco é 400; PUT em estornada é 422")
    void deveEstornar() throws Exception {
        String id = createExpense("100.00").get("id").asString();
        assertThat(balance(nubank)).isEqualTo("19900.00");

        MvcResult result = voidIt(id, "lançado em duplicidade").andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.status").value("VOIDED")).andExpect(jsonPath("$.voidReason").value("lançado em duplicidade"))
                .andExpect(jsonPath("$.voidable").value(false)).andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.voidBlockedBy").doesNotExist()).andExpect(jsonPath("$.impact.cash.amount").value("0.00"))
                .andExpect(jsonPath("$.impact.consumption").isEmpty()).andExpect(jsonPath("$.version").value(1)).andReturn();

        assertThat(body(result).get("voidedAt").asString()).isNotBlank();
        assertThat(activePostings(id)).isZero();
        assertThat(count("select count(*) from ledger_postings where transaction_id = ? and not active and deactivation_reason = 'VOID'", UUID.fromString(id))).isEqualTo(2);
        assertThat(balance(nubank)).isEqualTo("20000.00");
        assertThat(fetch(id)).isEqualTo(body(result));

        voidIt(id, "de novo").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_VOIDED"))
                .andExpect(ContractValidation.conforms());
        edit(id, expenseJson("EXPENSE", "10.00", food, ",\"version\":1")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_EDITABLE")).andExpect(ContractValidation.conforms());

        String other = createExpense("10.00").get("id").asString();
        mockMvc.perform(post("/v1/transactions/" + other + "/void", "{\"reason\":\" \"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason")).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/transactions/" + other + "/void", "{}")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        assertThat(fetch(other).get("status").asString()).isEqualTo("ACTIVE");
        voidIt(UUID.randomUUID().toString(), "x").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("CA6: transferência Nubank → XP com a XP arquivada depois: estornar e editar são 422 ACCOUNT_ARCHIVED, coerente com as flags; desarquivada, volta a estornar")
    void deveRecusarComContaArquivada() throws Exception {
        String first = create("{\"type\":\"TRANSFER\",\"amount\":\"1000.00\",\"date\":\"2026-10-10\",\"description\":\"Para a XP\",\"fromAccountId\":\""
                + nubank + "\",\"toAccountId\":\"" + xp + "\"}").get("id").asString();
        create("{\"type\":\"TRANSFER\",\"amount\":\"1000.00\",\"date\":\"2026-10-10\",\"description\":\"De volta\",\"fromAccountId\":\""
                + xp + "\",\"toAccountId\":\"" + nubank + "\"}");
        mockMvc.perform(post("/v1/accounts/" + xp + "/archive", "")).andExpect(status().isOk());

        voidIt(first, "engano").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ACCOUNT_ARCHIVED"))
                .andExpect(ContractValidation.conforms());
        edit(first, "{\"type\":\"TRANSFER\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"fromAccountId\":\"" + nubank
                + "\",\"toAccountId\":\"" + xp + "\",\"version\":0}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ARCHIVED")).andExpect(ContractValidation.conforms());
        JsonNode flags = fetch(first);
        assertThat(flags.get("editable").asBoolean()).isFalse();
        assertThat(flags.get("voidable").asBoolean()).isFalse();
        assertThat(flags.get("voidBlockedBy").asString()).isEqualTo("ACCOUNT_ARCHIVED");
        assertThat(flags.get("status").asString()).isEqualTo("ACTIVE");

        mockMvc.perform(post("/v1/accounts/" + xp + "/unarchive", "")).andExpect(status().isOk());
        voidIt(first, "engano").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED")).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("CA7: despesa com reembolso ativo vem voidable=false com HAS_ACTIVE_REFUNDS e o void é 422 TRANSACTION_HAS_ACTIVE_REFUNDS sem mudar nada; estornando o reembolso, a despesa estorna")
    void deveBloquearEstornoDaDespesaComReembolso() throws Exception {
        String expense = createExpense("100.00").get("id").asString();
        String refund = createRefund("50.00", expense).get("id").asString();

        JsonNode flags = fetch(expense);
        assertThat(flags.get("voidable").asBoolean()).isFalse();
        assertThat(flags.get("voidBlockedBy").asString()).isEqualTo("HAS_ACTIVE_REFUNDS");
        voidIt(expense, "engano").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_HAS_ACTIVE_REFUNDS"))
                .andExpect(ContractValidation.conforms());
        assertThat(fetch(expense).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(activePostings(expense)).isEqualTo(2);
        assertThat(fetch(expense).get("version").asLong()).isZero();

        voidIt(refund, "reembolso errado").andExpect(status().isOk()).andExpect(ContractValidation.conforms());
        JsonNode after = fetch(expense);
        assertThat(after.get("voidable").asBoolean()).isTrue();
        assertThat(after.get("voidBlockedBy").isNull()).isTrue();
        voidIt(expense, "engano").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));
        assertThat(balance(nubank)).isEqualTo("20000.00");
    }

    private AutoCloseable holdRowLock(String transactionId) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        Future<?> held = holder.submit(() -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForList("select id from transactions where id = ? for update", UUID.fromString(transactionId));
                locked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });
        assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return () -> {
            release.countDown();
            held.get();
            holder.shutdown();
        };
    }

    private void assertBlockedUntilReleased(Callable<MvcResult> request, AutoCloseable lock, int expectedStatus) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> future = pool.submit(request);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> future.get(700, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("a requisição deve esperar a trava da despesa").isInstanceOf(java.util.concurrent.TimeoutException.class);
            lock.close();
            assertThat(future.get(10, java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(expectedStatus);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("B1: criar reembolso, editar e estornar a despesa esperam a trava (FOR UPDATE) da linha da despesa original")
    void deveTravarADespesaOriginal() throws Exception {
        String expense = createExpense("100.00").get("id").asString();

        assertBlockedUntilReleased(() -> mockMvc.perform(post("/v1/transactions", refundJson("30.00", expense, food, "")).header("Idempotency-Key", UUID.randomUUID().toString())).andReturn(), holdRowLock(expense), 201);
        assertBlockedUntilReleased(() -> edit(expense, expenseJson("EXPENSE", "100.00", food, ",\"version\":0")).andReturn(),
                holdRowLock(expense), 200);
        assertBlockedUntilReleased(() -> voidIt(expense, "engano").andReturn(), holdRowLock(expense), 422);
    }

    @Test
    @DisplayName("B1: dois reembolsos de 60,00 em paralelo sobre 100,00: um 201 e um 422 REFUND_EXCEEDS_ORIGINAL; Σ ativos 60,00")
    void deveRespeitarOTetoComReembolsosEmParalelo() throws Exception {
        for (int round = 0; round < 3; round++) {
            String expense = createExpense("100.00").get("id").asString();
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<MvcResult>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        go.await();
                        return mockMvc.perform(post("/v1/transactions", refundJson("60.00", expense, food, "")).header("Idempotency-Key", UUID.randomUUID().toString())).andReturn();
                    }));
                }
                go.countDown();
                List<Integer> statuses = new ArrayList<>();
                List<String> codes = new ArrayList<>();
                for (Future<MvcResult> f : futures) {
                    MvcResult r = f.get();
                    statuses.add(r.getResponse().getStatus());
                    if (r.getResponse().getStatus() == 422) {
                        codes.add(body(r).get("code").asString());
                    }
                }
                assertThat(statuses).containsExactlyInAnyOrder(201, 422);
                assertThat(codes).containsExactly("REFUND_EXCEEDS_ORIGINAL");
            } finally {
                pool.shutdownNow();
            }
            assertThat(jdbc.queryForObject("select sum(amount_cents) from transactions where refund_of_id = ? and status = 'ACTIVE'", Long.class,
                    UUID.fromString(expense))).isEqualTo(6000L);
        }
    }

    @Test
    @DisplayName("B9 (S-113): com a categoria arquivada, editar descrição e valor sem trocar de categoria é 200; trocar PARA uma arquivada é 422 CATEGORY_ARCHIVED")
    void deveEditarSemTrocarACategoriaArquivada() throws Exception {
        String id = createExpense("100.00").get("id").asString();
        String archivedCategory = category("Impostos");
        mockMvc.perform(post("/v1/categories/" + food + "/archive", "")).andExpect(status().isOk());

        edit(id, expenseJson("EXPENSE", "120.00", food, ",\"version\":0").replace("Restaurante", "Restaurante corrigido")).andExpect(status().isOk())
                .andExpect(jsonPath("$.amount.amount").value("120.00")).andExpect(jsonPath("$.description").value("Restaurante corrigido"))
                .andExpect(jsonPath("$.category.id").value(food)).andExpect(ContractValidation.conforms());

        mockMvc.perform(post("/v1/categories/" + archivedCategory + "/archive", "")).andExpect(status().isOk());
        edit(id, expenseJson("EXPENSE", "120.00", archivedCategory, ",\"version\":1")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_ARCHIVED")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/transactions", expenseJson("EXPENSE", "10.00", food, "")).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CATEGORY_ARCHIVED"));
        assertThat(fetch(id).get("version").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("isolamento: outro usuário não edita nem estorna (404) e nada muda")
    void deveIsolarPorUsuario() throws Exception {
        String id = createExpense("100.00").get("id").asString();
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@example.com"));
        String otherBearer = auth.bearer(other);

        mockMvc.perform(MockMvcRequestBuilders.put("/v1/transactions/" + id).header(HttpHeaders.AUTHORIZATION, otherBearer)
                .contentType(MediaType.APPLICATION_JSON).content(expenseJson("EXPENSE", "1.00", food, ",\"version\":0")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/transactions/" + id + "/void").header(HttpHeaders.AUTHORIZATION, otherBearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isNotFound());
        assertThat(fetch(id).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(fetch(id).get("version").asLong()).isZero();
    }

    @Test
    @DisplayName("transaction_revisions tem a PK, a FK de user_id, a FK composta (transaction_id, user_id) e a unicidade (transaction_id, revision)")
    void deveTerConstraintsNomeadas() {
        List<String> constraints = jdbc.queryForList("""
                select conname || ' ' || pg_get_constraintdef(oid) from pg_constraint where conrelid = 'transaction_revisions'::regclass""", String.class);

        assertThat(constraints).contains(
                "pk_transaction_revisions PRIMARY KEY (id)",
                "fk_transaction_revisions_user FOREIGN KEY (user_id) REFERENCES users(id)",
                "fk_transaction_revisions_transaction_user FOREIGN KEY (transaction_id, user_id) REFERENCES transactions(id, user_id)",
                "uq_transaction_revisions_transaction_revision UNIQUE (transaction_id, revision)");
    }
}
