package com.moneylog.infra.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
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
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;
import com.moneylog.util.SqlFixtures;

/**
 * T26b: idempotência real de {@code POST /v1/transactions} (S-72, S-87). Cenário de T26a (Nubank 20.000,00, relógio em
 * 10/10/2026 15:00 UTC), com relógio avançável para a validade de 24 h.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("Idempotência da criação de movimentações (T26b)")
class IdempotencyIT {

    private static final String SCOPE = "POST /v1/transactions";

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

    private UUID userId;
    private String bearer;
    private String nubank;
    private String xp;
    private String food;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        userId = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, userId, "idem-" + userId + "@moneylog.com"));
        bearer = auth.bearer(userId);
        nubank = account("Nubank", "20000.00");
        xp = account("XP", "0.00");
        Category category = Category.create(UUID.randomUUID(), userId, "Alimentação", CategoryKind.EXPENSE);
        committed(() -> SqlFixtures.insertCategory(jdbc, category));
        food = category.id().toString();
    }

    @AfterEach
    void restaurarRelogio() {
        clock.set(MutableClockConfig.INITIAL);
    }

    private void committed(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private MockHttpServletRequestBuilder post(String bearerValue, String url, String json) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, bearerValue).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private ResultActions create(String json, UUID key) throws Exception {
        return mockMvc.perform(post(bearer, "/v1/transactions", json).header("Idempotency-Key", key.toString()));
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String account(String name, String balance) throws Exception {
        return body(mockMvc.perform(post(bearer, "/v1/accounts", "{\"name\":\"" + name + "\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\""
                + balance + "\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private String expense(String amount) {
        return "{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"2026-10-10\",\"description\":\"Restaurante\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}";
    }

    private int transactionCount() {
        return jdbc.queryForObject("select count(*) from transactions where user_id = ?", Integer.class, userId);
    }

    @Test
    @DisplayName("CA1: o mesmo corpo com a mesma chave cria uma só movimentação; a segunda resposta é 201 com o mesmo id e o estado ATUAL")
    void deveFazerReplayComEstadoAtual() throws Exception {
        UUID key = UUID.randomUUID();
        JsonNode first = body(create(expense("100.00"), key).andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());

        // alguém editou o recurso entre os envios (a edição pela API é T27): o replay traz a versão nova
        committed(() -> jdbc.update("update transactions set description = 'Editada', version = version + 1 where id = ?",
                UUID.fromString(first.get("id").asString())));
        MvcResult replay = create(expense("100.00"), key).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.id").value(first.get("id").asString())).andExpect(jsonPath("$.description").value("Editada"))
                .andExpect(jsonPath("$.version").value(1)).andReturn();

        assertThat(replay.getResponse().getHeader("Location")).isEqualTo("/v1/transactions/" + first.get("id").asString());
        assertThat(transactionCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ledger_postings where transaction_id = ?", Integer.class,
                UUID.fromString(first.get("id").asString()))).isEqualTo(2);
    }

    @Test
    @DisplayName("CA2: dois envios em paralelo com a mesma chave: uma só movimentação, duas respostas 201 com o mesmo id, nenhuma 409/500")
    void deveSerializarEnviosEmParalelo() throws Exception {
        UUID key = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> send = () -> {
                ready.countDown();
                go.await();
                return create(expense("100.00"), key).andReturn();
            };
            Future<MvcResult> a = pool.submit(send);
            Future<MvcResult> b = pool.submit(send);
            ready.await();
            go.countDown();
            MvcResult resultA = a.get();
            MvcResult resultB = b.get();

            assertThat(resultA.getResponse().getStatus()).isEqualTo(201);
            assertThat(resultB.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(resultA).get("id")).isEqualTo(body(resultB).get("id"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(transactionCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where user_id = ?", Integer.class, userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA3: a mesma chave com outro comando é 409 IDEMPOTENCY_KEY_REUSED; JSON reordenado e amount 100.0 são o mesmo comando")
    void deveRecusarOutroComandoEAceitarOMesmo() throws Exception {
        UUID key = UUID.randomUUID();
        JsonNode first = body(create(expense("100.00"), key).andExpect(status().isCreated()).andReturn());

        create(expense("100.01"), key).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(ContractValidation.conforms());
        String reordered = "{\"method\":\"PIX\",\"categoryId\":\"" + food + "\",\"accountId\":\"" + nubank + "\",   \"description\":\"Restaurante\","
                + "\"date\":\"2026-10-10\",\"amount\":\"100.0\",\"type\":\"EXPENSE\"}";
        create(reordered, key).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(first.get("id").asString()));
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA4: um primeiro envio que falha com 422 não é memorizado: depois de desarquivar a conta, a mesma chave cria a movimentação")
    void naoDeveMemorizarErros() throws Exception {
        UUID key = UUID.randomUUID();
        mockMvc.perform(post(bearer, "/v1/accounts/" + xp + "/archive", "")).andExpect(status().isOk());
        String toXp = "{\"type\":\"TRANSFER\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"Para a XP\",\"fromAccountId\":\""
                + nubank + "\",\"toAccountId\":\"" + xp + "\"}";

        create(toXp, key).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ACCOUNT_ARCHIVED"))
                .andExpect(ContractValidation.conforms());
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where user_id = ?", Integer.class, userId)).isZero();

        mockMvc.perform(post(bearer, "/v1/accounts/" + xp + "/unarchive", "")).andExpect(status().isOk());
        create(toXp, key).andExpect(status().isCreated()).andExpect(ContractValidation.conforms());
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5: com a chave vencida (mais de 24 h) um corpo novo cria outra movimentação e sobrescreve a chave")
    void deveSobrescreverChaveVencida() throws Exception {
        UUID key = UUID.randomUUID();
        JsonNode first = body(create(expense("100.00"), key).andExpect(status().isCreated()).andReturn());

        clock.advance(Duration.ofHours(23));
        bearer = auth.bearer(userId);
        create(expense("200.00"), key).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        clock.advance(Duration.ofHours(2));
        bearer = auth.bearer(userId);
        JsonNode second = body(create(expense("200.00"), key).andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());

        assertThat(second.get("id")).isNotEqualTo(first.get("id"));
        assertThat(second.get("amount").get("amount").asString()).isEqualTo("200.00");
        assertThat(transactionCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select resource_id from idempotency_keys where user_id = ? and scope = ?", UUID.class, userId, SCOPE))
                .isEqualTo(UUID.fromString(second.get("id").asString()));
    }

    @Test
    @DisplayName("CA6: a mesma chave usada por outro usuário não conflita (única por user_id + scope + chave)")
    void deveIsolarPorUsuario() throws Exception {
        UUID key = UUID.randomUUID();
        JsonNode mine = body(create(expense("100.00"), key).andExpect(status().isCreated()).andReturn());

        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com"));
        String otherBearer = auth.bearer(other);
        MvcResult theirs = mockMvc.perform(post(otherBearer, "/v1/accounts", "{\"name\":\"Conta\",\"type\":\"CHECKING\",\"openingBalance\":"
                + "{\"amount\":\"500.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).andExpect(status().isCreated()).andReturn();
        Category category = Category.create(UUID.randomUUID(), other, "Casa", CategoryKind.EXPENSE);
        committed(() -> SqlFixtures.insertCategory(jdbc, category));
        String theirExpense = "{\"type\":\"EXPENSE\",\"amount\":\"100.00\",\"date\":\"2026-10-10\",\"description\":\"Restaurante\",\"accountId\":\""
                + body(theirs).get("id").asString() + "\",\"categoryId\":\"" + category.id() + "\",\"method\":\"PIX\"}";

        JsonNode created = body(mockMvc.perform(post(otherBearer, "/v1/transactions", theirExpense).header("Idempotency-Key", key.toString()))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());

        assertThat(created.get("id")).isNotEqualTo(mine.get("id"));
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where scope = ? and idempotency_key = ?", Integer.class, SCOPE, key)).isEqualTo(2);
    }

    @Test
    @DisplayName("CA7: idempotency_keys tem as constraints nomeadas e a FK de user_id → users")
    void deveTerConstraintsNomeadas() {
        List<String> constraints = jdbc.queryForList("""
                select conname || ' ' || pg_get_constraintdef(oid) from pg_constraint where conrelid = 'idempotency_keys'::regclass""", String.class);

        assertThat(constraints).contains(
                "pk_idempotency_keys PRIMARY KEY (id)",
                "fk_idempotency_keys_user FOREIGN KEY (user_id) REFERENCES users(id)",
                "uq_idempotency_keys_user_scope_key UNIQUE (user_id, scope, idempotency_key)");
    }
}
