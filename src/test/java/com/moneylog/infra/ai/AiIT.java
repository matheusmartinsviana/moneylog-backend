package com.moneylog.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

@DisplayName("IA sem chave: status, prévia, negação, AI_DISABLED, insights e preferências (Etapa 8)")
class AiIT extends E4ItBase {

    private String expense(String amount, String date) {
        return "{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Mercado\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}";
    }

    private JsonNode preview() throws Exception {
        return createdBody(post("/v1/ai/previews", "{\"purpose\":\"MONTHLY_SUMMARY\",\"scope\":{\"month\":\"2026-10\",\"language\":\"pt\"}}"));
    }

    @Test
    @DisplayName("status: desligada, sem nenhum campo de chave ou URL")
    void deveInformarIaDesligada() throws Exception {
        JsonNode s = okBody(get("/v1/ai/status"));

        assertThat(s.get("enabled").asBoolean()).isFalse();
        assertThat(s.get("provider").asString()).isEqualTo("none");
        assertThat(s.get("circuit").asString()).isEqualTo("CLOSED");
        assertThat(s.toString().toLowerCase()).doesNotContain("key").doesNotContain("secret").doesNotContain("baseurl");
    }

    @Test
    @DisplayName("prévia mostra o payload exato, minimizado; negar registra DENIED sem payload; aprovar sem IA é AI_DISABLED")
    void deveSeguirOFluxoDePreviaSemIa() throws Exception {
        created(expense("100.00", "2026-10-05"));
        JsonNode preview = preview();
        String payload = preview.get("redactedPayload").asString();
        assertThat(payload).contains("\"purpose\":\"MONTHLY_SUMMARY\"").doesNotContain(userId.toString()).doesNotContain("Mercado").doesNotContain("@");
        assertThat(preview.get("estimatedTokens").asInt()).isPositive();
        okBody(get("/v1/ai/previews/" + preview.get("previewId").asString()));

        mockMvc.perform(post("/v1/ai/analyses", "{\"previewId\":\"" + preview.get("previewId").asString() + "\",\"approve\":true}")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("AI_DISABLED")).andExpect(ContractValidation.conforms());

        JsonNode denied = createdBody(post("/v1/ai/analyses", "{\"previewId\":\"" + preview.get("previewId").asString() + "\",\"approve\":false}")
                .header("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(denied.get("status").asString()).isEqualTo("DENIED");
        assertThat(denied.get("fallback").asBoolean()).isTrue();
        assertThat(denied.get("redactedPayload").isNull()).isTrue();

        JsonNode list = okBody(get("/v1/ai/analyses?status=DENIED"));
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("items").get(0).get("payloadAvailable").asBoolean()).isFalse();
        okBody(get("/v1/ai/analyses/" + denied.get("id").asString()));
    }

    @Test
    @DisplayName("prévia e análise de outro usuário: 404")
    void naoDeveExporDadosDeOutroUsuario() throws Exception {
        JsonNode preview = preview();
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "ai-" + other + "@moneylog.com"));
        String otherBearer = auth.bearer(other);

        mockMvc.perform(getAs(otherBearer, "/v1/ai/previews/" + preview.get("previewId").asString())).andExpect(status().isNotFound());
        mockMvc.perform(postAs(otherBearer, "/v1/ai/analyses", "{\"previewId\":\"" + preview.get("previewId").asString() + "\",\"approve\":false}")
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isNotFound());
    }

    @Test
    void deveRecusarFinalidadeSemFatosMontados() throws Exception {
        mockMvc.perform(post("/v1/ai/previews", "{\"purpose\":\"CATEGORIZE_IMPORT\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AI_PURPOSE_NOT_SUPPORTED")).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("insights determinísticos funcionam sem IA: Alimentação 500,00 -> 684,50")
    void deveGerarInsightsSemIa() throws Exception {
        created(expense("500.00", "2026-09-15"));
        created(expense("684.50", "2026-10-05"));

        JsonNode insights = okBody(get("/v1/insights?month=2026-10"));

        assertThat(insights.get("items")).hasSize(1);
        JsonNode delta = insights.get("items").get(0);
        assertThat(delta.get("type").asString()).isEqualTo("CATEGORY_DELTA");
        assertThat(delta.get("params").get("deltaAmount").asString()).isEqualTo("184.50");
        assertThat(delta.get("source").asString()).isEqualTo("COMPUTED");
        assertThat(okBody(get("/v1/insights?month=2025-01")).get("items")).isEmpty();
    }

    @Test
    void deveRecusarMesInvalidoNosInsights() throws Exception {
        mockMvc.perform(get("/v1/insights?month=2026-13")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("preferências: padrão desligado; ligar a mensagem diária sem provedor é AI_DISABLED")
    void deveGerenciarPreferencias() throws Exception {
        assertThat(okBody(get("/v1/settings/ai")).get("dailyMessageEnabled").asBoolean()).isFalse();
        mockMvc.perform(put("/v1/settings/ai", "{\"dailyMessageEnabled\":true}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AI_DISABLED"));
        assertThat(okBody(put("/v1/settings/ai", "{\"dailyMessageEnabled\":false}")).get("dailyMessageEnabled").asBoolean()).isFalse();
    }
}
