package com.moneylog.infra.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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

import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("Metas de medida, estágio, plano e consistência (ADR-016)")
class GoalMetricIT {

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
    private GoalSnapshotJob job;

    private UUID userId;
    private String bearer;
    private String card;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        userId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, userId, "gm-" + userId + "@moneylog.com"));
        bearer = auth.bearer(userId);
        String account = body(mockMvc.perform(post("/v1/accounts", "{\"name\":\"Conta\",\"type\":\"CHECKING\",\"institution\":\"Banco\","
                + "\"openingBalance\":{\"amount\":\"2000.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        card = body(mockMvc.perform(post("/v1/cards", "{\"name\":\"Cartao\",\"institution\":\"Banco\",\"limit\":{\"amount\":\"10000.00\","
                + "\"currency\":\"BRL\"},\"closingDay\":25,\"dueDay\":5,\"defaultPaymentAccountId\":\"" + account + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        mockMvc.perform(post("/v1/cards/" + card + "/ongoing-installments", "{\"description\":\"Item\",\"installmentAmount\":\"150.00\","
                + "\"totalInstallments\":10,\"remainingInstallments\":8}")).andExpect(status().isCreated());
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

    private JsonNode createMetric(String json) throws Exception {
        return body(mockMvc.perform(post("/v1/goals", json)).andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
    }

    private long snapshots(String goalId) {
        return jdbc.queryForObject("SELECT count(*) FROM goal_metric_snapshots WHERE goal_id = ?::uuid", Long.class, goalId);
    }

    @Test
    @DisplayName("METRIC: nasce com baseline = medida atual (mesma do overview), direção derivada, sem reservas, e 422/409/400/401/404 nos erros")
    void deveCriarELerMetaDeMedida() throws Exception {
        JsonNode overview = body(mockMvc.perform(get("/v1/debts/overview")).andExpect(status().isOk()).andReturn());
        String total = overview.get("total").get("amount").asString();
        assertThat(total).isEqualTo("1200.00");
        JsonNode goal = createMetric("{\"kind\":\"METRIC\",\"metric\":\"TOTAL_DEBT\",\"target\":\"0.00\"}");
        String id = goal.get("id").asString();
        assertThat(goal.get("kind").asString()).isEqualTo("METRIC");
        assertThat(goal.get("name").isNull()).isTrue();
        assertThat(goal.get("direction").asString()).isEqualTo("DECREASE");
        assertThat(goal.get("baseline").get("amount").asString()).isEqualTo(total);
        assertThat(goal.get("current").get("amount").asString()).isEqualTo(total);
        assertThat(goal.get("remaining").get("amount").asString()).isEqualTo(total);
        assertThat(goal.get("progress").asString()).isEqualTo("0.0000");
        assertThat(goal.get("reservations")).isEmpty();
        assertThat(goal.get("baselineSet").asBoolean()).isTrue();
        assertThat(goal.get("metricAsOf").asString()).isEqualTo("2026-10-10");
        assertThat(goal.get("stage").asString()).isEqualTo("FANTASY");
        assertThat(snapshots(id)).isEqualTo(1L);

        mockMvc.perform(post("/v1/cards/" + card + "/ongoing-installments", "{\"description\":\"Mais\",\"installmentAmount\":\"100.00\","
                + "\"totalInstallments\":2,\"remainingInstallments\":2}")).andExpect(status().isCreated());
        JsonNode read = body(mockMvc.perform(get("/v1/goals/" + id)).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(read.get("current").get("amount").asString()).isEqualTo("1400.00");
        assertThat(read.get("progress").asString()).isEqualTo("0.0000");
        JsonNode list = body(mockMvc.perform(get("/v1/goals")).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(list.get("items")).hasSize(1);

        mockMvc.perform(post("/v1/goals", "{\"kind\":\"METRIC\",\"metric\":\"TOTAL_DEBT\",\"target\":\"0.00\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GOAL_METRIC_DUPLICATE"));
        mockMvc.perform(post("/v1/goals", "{\"kind\":\"METRIC\",\"metric\":\"CARD_DEBT\",\"target\":\"1400.00\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GOAL_METRIC_TARGET_EQUALS_BASELINE"));
        mockMvc.perform(post("/v1/goals", "{\"kind\":\"METRIC\",\"target\":\"1.00\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/goals", "{\"kind\":\"METRIC\",\"metric\":\"CARD_DEBT\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/goals/" + id)).andExpect(status().isUnauthorized());
        UUID other = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, other, "gm2-" + other + "@moneylog.com"));
        bearer = auth.bearer(other);
        mockMvc.perform(get("/v1/goals/" + id)).andExpect(status().isNotFound());
        assertThat(body(mockMvc.perform(get("/v1/goals")).andReturn()).get("items")).isEmpty();
        mockMvc.perform(post("/v1/goals/" + id + "/actions", "{\"title\":\"x\",\"frequency\":\"MONTHLY\"}")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("sem cartões a medida do cartão vale 0,00 (não é erro); saldo em contas e disponível seguem o dashboard; custo fixo o resumo de recorrências")
    void deveSeguirAsFontes() throws Exception {
        UUID other = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, other, "gm3-" + other + "@moneylog.com"));
        bearer = auth.bearer(other);
        mockMvc.perform(post("/v1/goals", "{\"kind\":\"METRIC\",\"metric\":\"ACCOUNTS_BALANCE\",\"target\":\"10.00\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.current.amount").value("0.00")).andExpect(jsonPath("$.direction").value("INCREASE"));
        mockMvc.perform(post("/v1/goals", "{\"kind\":\"METRIC\",\"metric\":\"CARD_DEBT\",\"target\":\"10.00\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.current.amount").value("0.00"));
        bearer = auth.bearer(userId);
        JsonNode balances = body(mockMvc.perform(get("/v1/dashboard/summary")).andExpect(status().isOk()).andReturn()).get("balances");
        JsonNode accounts = createMetric("{\"kind\":\"METRIC\",\"metric\":\"ACCOUNTS_BALANCE\",\"target\":\"5000.00\"}");
        JsonNode available = createMetric("{\"kind\":\"METRIC\",\"metric\":\"AVAILABLE_BALANCE\",\"target\":\"5000.00\"}");
        assertThat(accounts.get("current").get("amount").asString()).isEqualTo(balances.get("inAccounts").get("amount").asString());
        assertThat(available.get("current").get("amount").asString()).isEqualTo(balances.get("available").get("amount").asString());
        JsonNode fixed = body(mockMvc.perform(get("/v1/recurrences/summary")).andExpect(status().isOk()).andReturn());
        JsonNode fixedGoal = createMetric("{\"kind\":\"METRIC\",\"metric\":\"FIXED_COSTS_MONTHLY\",\"target\":\"100.00\",\"name\":\"Enxugar\"}");
        assertThat(fixedGoal.get("current").get("amount").asString()).isEqualTo(fixed.get("fixedCosts").get("amount").asString());
        assertThat(fixedGoal.get("name").asString()).isEqualTo("Enxugar");
    }

    @Test
    @DisplayName("editar e re-ancorar: PUT sem nome/alvo mantém o alvo, muda prazo e plano; baseline re-ancora com o valor de agora; arquivada não calcula e desarquivar respeita duplicata")
    void deveEditarReancorarEArquivar() throws Exception {
        JsonNode goal = createMetric("{\"kind\":\"METRIC\",\"metric\":\"TOTAL_DEBT\",\"target\":\"600.00\"}");
        String id = goal.get("id").asString();
        long version = goal.get("version").asLong();
        JsonNode edited = body(mockMvc.perform(put("/v1/goals/" + id, "{\"deadline\":\"2027-06-30\",\"planNote\":\"Pagar 150 por mês\",\"version\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(edited.get("target").get("amount").asString()).isEqualTo("600.00");
        assertThat(edited.get("stage").asString()).isEqualTo("STRATEGY");
        assertThat(edited.get("monthsLeft").asInt()).isEqualTo(9);
        assertThat(edited.get("requiredPace").get("amount").asString()).isEqualTo("66.67");
        assertThat(edited.get("onTrack").asBoolean()).isTrue();
        JsonNode noDeadline = body(mockMvc.perform(put("/v1/goals/" + id, "{\"planNote\":\"Pagar 150 por mês\",\"version\":" + edited.get("version").asLong() + "}"))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(noDeadline.get("stage").asString()).isEqualTo("FANTASY");
        assertThat(noDeadline.get("requiredPace").isNull()).isTrue();
        mockMvc.perform(put("/v1/goals/" + id, "{\"target\":\"1200.00\",\"version\":" + noDeadline.get("version").asLong() + "}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("GOAL_METRIC_TARGET_EQUALS_BASELINE"));
        createMetric("{\"kind\":\"METRIC\",\"metric\":\"TOTAL_DEBT\",\"target\":\"700.00\"}");
        mockMvc.perform(put("/v1/goals/" + id, "{\"target\":\"700.00\",\"version\":" + noDeadline.get("version").asLong() + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GOAL_METRIC_DUPLICATE"));
        mockMvc.perform(post("/v1/cards/" + card + "/ongoing-installments", "{\"description\":\"Mais\",\"installmentAmount\":\"100.00\","
                + "\"totalInstallments\":2,\"remainingInstallments\":2}")).andExpect(status().isCreated());
        mockMvc.perform(post("/v1/goals/" + id + "/baseline", "")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.baseline.amount").value("1400.00")).andExpect(jsonPath("$.progress").value("0.0000"));
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/goals/" + id + "/reservations").header(HttpHeaders.AUTHORIZATION, bearer)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\"}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/v1/goals/" + id + "/archive", "")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.status").value("ARCHIVED")).andExpect(jsonPath("$.current").value((Object) null))
                .andExpect(jsonPath("$.remaining").value((Object) null)).andExpect(jsonPath("$.metricAsOf").value((Object) null));
    }

    @Test
    @DisplayName("plano declarado: ações (criar 201, editar, check-in idempotente, período inválido 422, remover, limite de 10); STRATEGY com prazo + ação")
    void deveGerirAcoes() throws Exception {
        JsonNode goal = createMetric("{\"kind\":\"METRIC\",\"metric\":\"TOTAL_DEBT\",\"target\":\"0.00\",\"deadline\":\"2027-09-30\"}");
        String id = goal.get("id").asString();
        assertThat(goal.get("stage").asString()).isEqualTo("OBJECTIVE");
        assertThat(goal.get("requiredPace").get("amount").asString()).isEqualTo("100.00");
        MvcResult added = mockMvc.perform(post("/v1/goals/" + id + "/actions", "{\"title\":\"Pagar a fatura em dia\",\"frequency\":\"MONTHLY\"}"))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.stage").value("STRATEGY")).andReturn();
        assertThat(added.getResponse().getHeader("Location")).isEqualTo("/v1/goals/" + id);
        String action = body(added).get("actions").get(0).get("id").asString();
        String checkIn = "/v1/goals/" + id + "/actions/" + action + "/check-in";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(checkIn, "{\"period\":\"2026-10\"}")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                    .andExpect(jsonPath("$.actions[0].doneIn.length()").value(1));
        }
        mockMvc.perform(post(checkIn, "{\"period\":\"2026-W41\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GOAL_CHECKIN_PERIOD_INVALID"));
        mockMvc.perform(post(checkIn, "{\"period\":\"2026-10\",\"done\":false}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].doneIn.length()").value(0));
        mockMvc.perform(post(checkIn, "{\"period\":\"2026-11\"}")).andExpect(status().isOk());
        mockMvc.perform(put("/v1/goals/" + id + "/actions/" + action, "{\"title\":\"Pagar toda semana\",\"frequency\":\"WEEKLY\"}"))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.actions[0].doneIn.length()").value(0));
        mockMvc.perform(post("/v1/goals/" + id + "/actions", "{\"title\":\"\",\"frequency\":\"MONTHLY\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/v1/goals/" + id + "/actions/" + UUID.randomUUID(), "{\"title\":\"x\",\"frequency\":\"MONTHLY\"}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GOAL_ACTION_NOT_FOUND"));
        for (int i = 1; i < 10; i++) {
            mockMvc.perform(post("/v1/goals/" + id + "/actions", "{\"title\":\"a" + i + "\",\"frequency\":\"ONE_TIME\"}")).andExpect(status().isCreated());
        }
        mockMvc.perform(post("/v1/goals/" + id + "/actions", "{\"title\":\"11\",\"frequency\":\"ONE_TIME\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GOAL_ACTION_LIMIT"));
        mockMvc.perform(MockMvcRequestBuilders.delete("/v1/goals/" + id + "/actions/" + action).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.actions.length()").value(9));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_action_checkins WHERE user_id = ?", Long.class, userId)).isZero();
    }

    @Test
    @DisplayName("instantâneos: criar grava um; o job é idempotente; a leitura grava o do mês se faltar; meses sem dado ficam 'sem dado' e a sequência conta só o que cumpriu")
    void deveGravarInstantaneosECalcularConsistencia() throws Exception {
        JsonNode goal = createMetric("{\"kind\":\"METRIC\",\"metric\":\"TOTAL_DEBT\",\"target\":\"0.00\",\"deadline\":\"2027-09-30\"}");
        String id = goal.get("id").asString();
        assertThat(snapshots(id)).isEqualTo(1L);
        job.runOnce();
        job.runOnce();
        assertThat(snapshots(id)).as("job idempotente: um por meta e mês").isEqualTo(1L);
        write("DELETE FROM goal_metric_snapshots WHERE goal_id = ?::uuid", id);
        mockMvc.perform(get("/v1/goals/" + id)).andExpect(status().isOk());
        assertThat(snapshots(id)).as("primeira leitura do mês").isEqualTo(1L);
        mockMvc.perform(get("/v1/goals/" + id)).andExpect(status().isOk());
        assertThat(snapshots(id)).isEqualTo(1L);

        String action = body(mockMvc.perform(post("/v1/goals/" + id + "/actions", "{\"title\":\"Pagar\",\"frequency\":\"MONTHLY\"}")).andReturn())
                .get("actions").get(0).get("id").asString();
        assertThat(action).isNotBlank();
        write("UPDATE goal_metric_snapshots SET value_cents = 100000 WHERE goal_id = ?::uuid", id);
        insertSnapshot(id, "2026-12-01", 90000L);
        clock.set(MutableClockConfig.INITIAL.plus(Duration.ofDays(100)));
        bearer = auth.bearer(userId);
        JsonNode later = body(mockMvc.perform(get("/v1/goals/" + id)).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        JsonNode history = later.get("consistency");
        assertThat(history).hasSize(4);
        assertThat(history.get(0).get("met").asBoolean()).isTrue();
        assertThat(history.get(1).get("met").isNull()).as("nov sem instantâneo = sem dado, nunca cumprido").isTrue();
        assertThat(history.get(1).get("actual").isNull()).isTrue();
        assertThat(history.get(2).get("met").asBoolean()).isTrue();
        assertThat(history.get(3).get("closed").asBoolean()).isFalse();
        assertThat(later.get("streak").asInt()).isEqualTo(1);
        assertThat(later.get("consistencyRate").asString()).isEqualTo("1.0000");
        assertThat(later.get("stage").asString()).isEqualTo("STRATEGY");
        insertSnapshot(id, "2026-11-01", 95000L);
        JsonNode consistent = body(mockMvc.perform(get("/v1/goals/" + id)).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(consistent.get("streak").asInt()).isEqualTo(3);
        assertThat(consistent.get("stage").asString()).isEqualTo("CONSISTENT");
    }

    private void insertSnapshot(String goalId, String month, long cents) {
        write("INSERT INTO goal_metric_snapshots (id, user_id, goal_id, reference_month, value_cents, currency, captured_at, created_at, updated_at)"
                + " VALUES (?, ?, ?::uuid, ?::date, ?, 'BRL', now(), now(), now())", UUID.randomUUID(), userId, goalId, month, cents);
    }

    private void write(String sql, Object... args) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> jdbc.update(sql, args));
    }
}
