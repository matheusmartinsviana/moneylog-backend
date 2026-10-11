package com.moneylog.infra.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

@DisplayName("Insights calculados: daily, monthly, history, dispensa auditada e isolamento (T293, T294)")
class InsightsIT extends E4ItBase {

    private void salaryAndExpense() throws Exception {
        created("{\"type\":\"INCOME\",\"amount\":\"4000.00\",\"date\":\"2026-10-02\",\"description\":\"Folha\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + salary + "\",\"method\":\"TRANSFER\"}");
        created("{\"type\":\"EXPENSE\",\"amount\":\"300.00\",\"date\":\"2026-10-05\",\"description\":\"Mercado\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}");
    }

    private MockHttpServletRequestBuilder delete(String url) {
        return MockMvcRequestBuilders.delete(url).header(HttpHeaders.AUTHORIZATION, bearer);
    }

    private static JsonNode find(JsonNode list, String type) {
        for (JsonNode item : list.get("items")) {
            if (item.get("type").asString().equals(type)) {
                return item;
            }
        }
        return null;
    }

    private JsonNode dailyBudget() throws Exception {
        return find(okBody(get("/v1/insights/daily?includeDismissed=true")), "DAILY_BUDGET");
    }

    @Test
    @DisplayName("daily: orçamento do dia = (4.000 − 300) / 22 dias = 168,18, com o contrato novo completo (id, severity, priority, period, source, basis)")
    void deveEmitirOrcamentoDoDia() throws Exception {
        salaryAndExpense();

        JsonNode daily = okBody(get("/v1/insights/daily"));

        JsonNode budget = find(daily, "DAILY_BUDGET");
        assertThat(budget).isNotNull();
        assertThat(budget.get("id").asString()).matches("ins_[0-9a-f]{32}");
        assertThat(budget.get("key").asString()).isEqualTo("insight.daily.budget");
        assertThat(budget.get("params").get("amount").asString()).isEqualTo("168.18");
        assertThat(budget.get("params").get("daysLeft").asString()).isEqualTo("22");
        assertThat(budget.get("severity").asString()).isEqualTo("INFO");
        assertThat(budget.get("period").get("kind").asString()).isEqualTo("DAY");
        assertThat(budget.get("period").get("value").asString()).isEqualTo("2026-10-10");
        assertThat(budget.get("source").asString()).isEqualTo("COMPUTED");
        assertThat(budget.get("dismissed").asBoolean()).isFalse();
        assertThat(daily.get("insufficient").isArray()).isTrue();
        assertThat(daily.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("daily sem renda recebida: não emite o orçamento e diz por quê (insufficient NO_INCOME)")
    void naoDeveEmitirSemRenda() throws Exception {
        JsonNode daily = okBody(get("/v1/insights/daily"));

        assertThat(find(daily, "DAILY_BUDGET")).isNull();
        boolean reported = false;
        for (JsonNode d : daily.get("insufficient")) {
            reported |= d.get("type").asString().equals("DAILY_BUDGET") && d.get("reason").asString().equals("NO_INCOME");
        }
        assertThat(reported).isTrue();
    }

    @Test
    @DisplayName("monthly: dinheiro livre de outubro e o contrato; mês sem dados sai vazio; mês futuro diz FUTURE_MONTH")
    void deveEmitirOsInsightsDoMes() throws Exception {
        salaryAndExpense();

        JsonNode monthly = okBody(get("/v1/insights/monthly?month=2026-10"));

        JsonNode free = find(monthly, "FREE_MONEY");
        assertThat(free.get("params").get("free").asString()).isEqualTo("3700.00");
        assertThat(free.get("period").get("value").asString()).isEqualTo("2026-10");
        assertThat(okBody(get("/v1/insights/monthly?month=2025-01")).get("items")).isEmpty();
        JsonNode future = okBody(get("/v1/insights/monthly?month=2027-05"));
        assertThat(future.get("items")).isEmpty();
        assertThat(future.get("insufficient").get(0).get("reason").asString()).isEqualTo("FUTURE_MONTH");
    }

    @Test
    @DisplayName("history: 6 meses, um item por mês em ordem; máximo de 12 meses; intervalo invertido é 400")
    void deveListarOHistorico() throws Exception {
        salaryAndExpense();

        JsonNode history = okBody(get("/v1/insights/history?from=2026-05&to=2026-10"));

        assertThat(history.get("items")).hasSize(6);
        assertThat(history.get("items").get(0).get("month").asString()).isEqualTo("2026-05");
        assertThat(history.get("items").get(5).get("month").asString()).isEqualTo("2026-10");
        assertThat(find(history.get("items").get(5), "FREE_MONEY")).isNotNull();
        mockMvc.perform(get("/v1/insights/history?from=2025-09&to=2026-10")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSIGHTS_RANGE_INVALID")).andExpect(ContractValidation.conforms());
        mockMvc.perform(get("/v1/insights/history?from=2026-10&to=2026-05")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSIGHTS_RANGE_INVALID"));
        okBody(get("/v1/insights/history?from=2025-11&to=2026-10"));
    }

    @Test
    @DisplayName("entradas inválidas: data futura (INSIGHTS_DATE_INVALID), mês e limite inválidos, escopo desconhecido: 400 conforme o contrato")
    void deveRecusarEntradasInvalidas() throws Exception {
        mockMvc.perform(get("/v1/insights/daily?date=2026-10-11")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSIGHTS_DATE_INVALID")).andExpect(ContractValidation.conforms());
        mockMvc.perform(get("/v1/insights/monthly?month=2026-13")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSIGHTS_MONTH_INVALID"));
        mockMvc.perform(get("/v1/insights/monthly?limit=0")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INSIGHTS_LIMIT_INVALID"));
        mockMvc.perform(get("/v1/insights/monthly?limit=51")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/insights/ins_" + "a".repeat(32) + "/dismissals", "{\"scope\":\"FOREVER\"}")).andExpect(status().isBadRequest());
        okBody(get("/v1/insights/daily?date=2026-10-10"));
    }

    @Test
    @DisplayName("sem token: 401 em todas as rotas")
    void deveExigirAutenticacao() throws Exception {
        for (String url : List.of("/v1/insights", "/v1/insights/monthly", "/v1/insights/daily", "/v1/insights/history?from=2026-01&to=2026-02")) {
            mockMvc.perform(MockMvcRequestBuilders.get(url)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("alias GET /v1/insights?month=: um cliente antigo, que só lê type, key, params e factRefs, continua parseando")
    void deveManterOContratoAntigo() throws Exception {
        salaryAndExpense();
        created("{\"type\":\"EXPENSE\",\"amount\":\"500.00\",\"date\":\"2026-09-15\",\"description\":\"Mercado\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}");
        created("{\"type\":\"EXPENSE\",\"amount\":\"684.50\",\"date\":\"2026-10-06\",\"description\":\"Mercado\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}");

        JsonNode alias = okBody(get("/v1/insights?month=2026-10"));

        JsonNode delta = find(alias, "CATEGORY_DELTA");
        assertThat(delta).isNotNull();
        for (String field : List.of("type", "key", "params", "factRefs", "source")) {
            assertThat(delta.has(field)).as(field).isTrue();
        }
        assertThat(delta.size()).as("o alias devolve só os cinco campos originais, nenhum campo novo").isEqualTo(5);
        assertThat(find(alias, "FREE_MONEY")).as("nem tipos que o contrato antigo não conhecia").isNull();
        alias.get("items").forEach(i -> assertThat(List.of("CATEGORY_DELTA", "CARD_COMMITTED")).contains(i.get("type").asString()));
        assertThat(alias.has("insufficient")).isFalse();
        assertThat(delta.get("key").asString()).isEqualTo("insight.category.up");
        assertThat(delta.get("params").get("categoryName").asString()).isEqualTo("Alimentação");
        assertThat(delta.get("params").get("deltaAmount").asString()).as("outubro 984,50 contra setembro 500,00").isEqualTo("484.50");
        assertThat(delta.get("params").get("baseline").asString()).isEqualTo("PREVIOUS_MONTH");
        assertThat(okBody(get("/v1/insights?month=2025-01")).get("items")).isEmpty();
        mockMvc.perform(get("/v1/insights?month=2026-13")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INSIGHTS_MONTH_INVALID"));
        assertThat(okBody(get("/v1/insights?month=2026-10&page=1&pageSize=1")).get("items")).hasSize(1);
    }

    @Test
    @DisplayName("dispensar HOJE: 201 + Location, o insight some do dia (volta com includeDismissed), a linha é gravada e no dia seguinte o do novo dia aparece")
    void deveDispensarHoje() throws Exception {
        salaryAndExpense();
        String id = dailyBudget().get("id").asString();

        JsonNode dismissed = createdBody(post("/v1/insights/" + id + "/dismissals", "{\"scope\":\"TODAY\"}"));
        assertThat(dismissed.get("dismissed").asBoolean()).isTrue();
        assertThat(dismissed.get("id").asString()).isEqualTo(id);
        mockMvc.perform(post("/v1/insights/" + id + "/dismissals", "{\"scope\":\"TODAY\"}")).andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "/v1/insights/" + id + "/dismissals"));

        assertThat(find(okBody(get("/v1/insights/daily")), "DAILY_BUDGET")).as("some do dia").isNull();
        JsonNode withDismissed = find(okBody(get("/v1/insights/daily?includeDismissed=true")), "DAILY_BUDGET");
        assertThat(withDismissed.get("dismissed").asBoolean()).isTrue();
        assertThat(count("insight_dismissals")).as("duas chamadas iguais, uma linha só").isEqualTo(1);

        advanceTo(Instant.parse("2026-10-11T15:00:00Z"));
        JsonNode tomorrow = find(okBody(get("/v1/insights/daily")), "DAILY_BUDGET");
        assertThat(tomorrow).as("volta no dia seguinte").isNotNull();
        assertThat(tomorrow.get("id").asString()).isNotEqualTo(id);
        assertThat(tomorrow.get("dismissed").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("dispensar este MÊS esconde o insight nos outros dias do mês; PARAR DE AVISAR DO TIPO esconde no mês seguinte também")
    void deveDispensarPorMesEPorTipo() throws Exception {
        salaryAndExpense();
        String id = dailyBudget().get("id").asString();
        createdBody(post("/v1/insights/" + id + "/dismissals", "{\"scope\":\"MONTH\"}"));

        advanceTo(Instant.parse("2026-10-12T15:00:00Z"));
        assertThat(find(okBody(get("/v1/insights/daily")), "DAILY_BUDGET")).as("mesmo mês").isNull();

        advanceTo(Instant.parse("2026-11-03T15:00:00Z"));
        created("{\"type\":\"INCOME\",\"amount\":\"4000.00\",\"date\":\"2026-11-02\",\"description\":\"Folha\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + salary + "\",\"method\":\"TRANSFER\"}");
        String november = dailyBudget().get("id").asString();
        assertThat(find(okBody(get("/v1/insights/daily")), "DAILY_BUDGET")).as("mês seguinte volta").isNotNull();

        createdBody(post("/v1/insights/" + november + "/dismissals", "{\"scope\":\"TYPE\"}"));
        assertThat(find(okBody(get("/v1/insights/daily")), "DAILY_BUDGET")).isNull();
        advanceTo(Instant.parse("2026-12-10T15:00:00Z"));
        assertThat(find(okBody(get("/v1/insights/daily")), "DAILY_BUDGET")).as("tipo dispensado até reverter").isNull();
    }

    @Test
    @DisplayName("restaurar (DELETE): grava revoked_at, a linha de auditoria continua existindo e o insight volta")
    void deveRestaurarSemApagarAAuditoria() throws Exception {
        salaryAndExpense();
        String id = dailyBudget().get("id").asString();
        createdBody(post("/v1/insights/" + id + "/dismissals", "{\"scope\":\"TODAY\"}"));

        JsonNode restored = okBody(delete("/v1/insights/" + id + "/dismissals"));

        assertThat(restored.get("dismissed").asBoolean()).isFalse();
        assertThat(count("insight_dismissals")).as("a linha continua").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM insight_dismissals WHERE user_id = ?", Boolean.class, userId)).isTrue();
        assertThat(find(okBody(get("/v1/insights/daily")), "DAILY_BUDGET")).isNotNull();
        JsonNode again = okBody(delete("/v1/insights/" + id + "/dismissals"));
        assertThat(again.get("dismissed").asBoolean()).isFalse();
        assertThat(count("insight_dismissals")).isEqualTo(1);
    }

    @Test
    @DisplayName("a auditoria é só de inserção no banco: não apaga, não edita outra coisa e só revoga uma vez")
    void deveProtegerAAuditoriaNoBanco() throws Exception {
        salaryAndExpense();
        String id = dailyBudget().get("id").asString();
        createdBody(post("/v1/insights/" + id + "/dismissals", "{\"scope\":\"TODAY\"}"));

        assertThatThrownBy(() -> committed(() -> jdbc.update("DELETE FROM insight_dismissals WHERE user_id = ?", userId)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("só de inserção");
        assertThatThrownBy(() -> committed(() -> jdbc.update("UPDATE insight_dismissals SET scope = 'TYPE' WHERE user_id = ?", userId)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("só de inserção");
        committed(() -> assertThat(jdbc.update("UPDATE insight_dismissals SET revoked_at = now() WHERE user_id = ?", userId)).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM insight_dismissals WHERE user_id = ?", Boolean.class, userId)).isTrue();
        assertThatThrownBy(() -> committed(() -> jdbc.update("UPDATE insight_dismissals SET revoked_at = now() + interval '1 minute' WHERE user_id = ?",
                userId))).isInstanceOf(DataAccessException.class).hasMessageContaining("só de inserção");
        assertThat(count("insight_dismissals")).isEqualTo(1);
    }

    @Test
    @DisplayName("outro usuário: 404 ao dispensar ou restaurar o id alheio, nada gravado, e a própria lista não vê os dados do primeiro")
    void deveIsolarOsUsuarios() throws Exception {
        salaryAndExpense();
        String id = dailyBudget().get("id").asString();
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "ins-" + other + "@example.com"));
        String otherBearer = auth.bearer(other);

        mockMvc.perform(postAs(otherBearer, "/v1/insights/" + id + "/dismissals", "{\"scope\":\"TODAY\"}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INSIGHT_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(MockMvcRequestBuilders.delete("/v1/insights/" + id + "/dismissals").header(HttpHeaders.AUTHORIZATION, otherBearer))
                .andExpect(status().isNotFound());
        assertThat(count("insight_dismissals")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM insight_dismissals WHERE user_id = ?", Long.class, other)).isZero();

        JsonNode theirs = body(mockMvc.perform(getAs(otherBearer, "/v1/insights/daily")).andExpect(status().isOk()).andReturn());
        assertThat(find(theirs, "DAILY_BUDGET")).as("o outro usuário não tem renda: nada dele vem do primeiro").isNull();
        JsonNode theirMonthly = body(mockMvc.perform(getAs(otherBearer, "/v1/insights/monthly")).andExpect(status().isOk()).andReturn());
        assertThat(theirMonthly.get("items")).isEmpty();
        mockMvc.perform(post("/v1/insights/ins_" + "0".repeat(32) + "/dismissals", "{\"scope\":\"TODAY\"}")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("sem provedor de IA (padrão): daily e monthly respondem 200 com insights, e /v1/ai/status segue como antes")
    void deveFuncionarSemIa() throws Exception {
        salaryAndExpense();

        assertThat(okBody(get("/v1/ai/status")).get("enabled").asBoolean()).isFalse();
        assertThat(okBody(get("/v1/insights/daily")).get("items")).isNotEmpty();
        assertThat(okBody(get("/v1/insights/monthly")).get("items")).isNotEmpty();
        assertThat(okBody(get("/v1/ai/status")).get("provider").asString()).isEqualTo("none");
        List<String> types = new ArrayList<>();
        okBody(get("/v1/insights/monthly")).get("items").forEach(i -> types.add(i.get("type").asString()));
        assertThat(types).doesNotContain("AI");
    }
}
