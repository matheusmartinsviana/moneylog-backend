package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

@DisplayName("Contrato x resposta real dos blocos A a I (openapi.yaml 2.32.x): cada recurso novo conforme o schema publicado")
class ContractShapeBugsIT extends ResumaoItBase {

    @Test
    @DisplayName("recorrência com proveniência: a resposta conforma ao contrato (Provenance só com origin e confidence, sem campo derivado 'estimated')")
    void proveniênciaNaRecorrencia() throws Exception {
        basicWorld();
        JsonNode recurrence = createdBody(post("/v1/recurrences", "{\"type\":\"CARD_PURCHASE\",\"amount\":\"10.00\",\"description\":\"Servico\",\"categoryId\":\"" + catFood
                + "\",\"cardId\":\"" + cardA + "\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-11-05\",\"provenance\":{\"origin\":\"PRINT\",\"confidence\":\"ESTIMATED\"}}"));

        assertThat(recurrence.get("provenance").propertyNames()).containsExactlyInAnyOrder("origin", "confidence");
        assertThat(recurrence.get("provenance").get("confidence").asString()).isEqualTo("ESTIMATED");
    }

    @Test
    @DisplayName("prévia de compra sem rateio: o contrato não aceita splitMode nulo; a resposta omite o campo ou traz um modo válido")
    void previaSemRateio() throws Exception {
        basicWorld();

        JsonNode preview = okBody(post("/v1/cards/" + cardA + "/purchase-preview", "{\"amount\":\"100.00\",\"installments\":2,\"date\":\"2026-10-05\"}"));

        assertThat(preview.get("splitMode") == null || !preview.get("splitMode").isNull()).isTrue();
    }

    @Test
    @DisplayName("prévia do perfil de renda com rubrica fixa e com consignado: debtId sempre presente (nulo ou id), como exige o contrato")
    void previaDoPerfilComRubrica() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");

        JsonNode preview = okBody(get("/v1/income-profiles/" + ids.get("perfilRenda") + "/preview?month=2026-10"));

        assertThat(preview.get("fixedDeductions").get(0).has("debtId")).isTrue();
        assertThat(preview.get("loanDeductions").get(0).get("debtId").asString()).isEqualTo(ids.get("debtFolha"));
    }

    @Test
    @DisplayName("PUT do perfil de renda: o corpo com version precisa ser válido no schema IncomeProfileUpdate")
    void putDoPerfil() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        JsonNode profile = okBody(get("/v1/income-profiles/" + ids.get("perfilRenda")));

        okBody(put("/v1/income-profiles/" + ids.get("perfilRenda"), "{\"source\":\"Empresa Exemplo\",\"gross\":\"8000.00\",\"dependents\":1,\"advance\":null,"
                + "\"balanceDayOfMonth\":1,\"deductions\":[],\"thirteenth\":{\"enabled\":false,\"firstInstallmentMonth\":11},\"version\":" + profile.get("version").asLong() + "}"));
    }

    @Test
    @DisplayName("insights daily e monthly devolvem view (MINE por padrão, GROSS aceito) e seguem o schema; view inválida 400")
    void insightsComView() throws Exception {
        basicWorld();
        created(sharedPurchase(cardA, "100.00", 1, "2026-10-05", "BPS", "[" + share(null, 5000) + "," + share(ana, 5000) + "]"));

        assertThat(okBody(get("/v1/insights/daily")).get("view").asString()).isEqualTo("MINE");
        assertThat(okBody(get("/v1/insights/daily?view=GROSS")).get("view").asString()).isEqualTo("GROSS");
        assertThat(okBody(get("/v1/insights/monthly?month=2026-10")).get("view").asString()).isEqualTo("MINE");
        mockMvc.perform(get("/v1/insights/daily?view=NADA")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /v1/recurrences/summary devolve view (MINE por padrão, GROSS aceito) e segue o schema; recorrência rateada conta só a minha parte em MINE")
    void resumoDeRecorrenciasComView() throws Exception {
        basicWorld();
        createdBody(post("/v1/recurrences", "{\"type\":\"CARD_PURCHASE\",\"amount\":\"100.00\",\"description\":\"Mensalidade\",\"categoryId\":\"" + catFood
                + "\",\"cardId\":\"" + cardA + "\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-11-05\",\"splitMode\":\"BPS\",\"shares\":["
                + share(null, 5000) + "," + share(ana, 5000) + "]}"));

        JsonNode mine = okBody(get("/v1/recurrences/summary"));
        JsonNode gross = okBody(get("/v1/recurrences/summary?view=GROSS"));

        assertThat(mine.get("view").asString()).isEqualTo("MINE");
        assertThat(gross.get("view").asString()).isEqualTo("GROSS");
        assertThat(cents(mine.get("fixedCosts")) * 2).isEqualTo(cents(gross.get("fixedCosts")));
        assertThat(money(gross, "fixedCosts")).isEqualTo("100.00");
    }

    @Test
    @DisplayName("recorrência de salário com saldo no mês seguinte (incomeCompetenceOffsetMonths = -1, ADR-017 emendada): GET /v1/recurrences conforma ao contrato")
    void deslocamentoMenosUmNoContrato() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        mockMvc.perform(post("/v1/income-profiles/" + ids.get("perfilRenda") + "/generate-occurrences?from=2026-10&to=2026-10", "")).andExpect(status().isOk());

        JsonNode list = okBody(get("/v1/recurrences?pageSize=50"));

        boolean found = false;
        for (JsonNode item : list.get("items")) {
            if (item.has("incomeCompetenceOffsetMonths") && item.get("incomeCompetenceOffsetMonths").asInt() == -1) {
                found = true;
            }
        }
        assertThat(found).as("a recorrência do saldo tem deslocamento -1").isTrue();
    }
}
