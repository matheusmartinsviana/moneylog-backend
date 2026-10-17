package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

@DisplayName("E2: simulador posso comprar? (ADR-030): impacto mês a mês, cortes, financiamento pendente, classificação, horizonte e não gravar")
class PurchaseSimulationIT extends ResumaoItBase {

    private String simulate(String amount, int installments, String month, String extra) {
        return "{\"amount\":\"" + amount + "\",\"installments\":" + installments + ",\"firstInstallmentMonth\":\"" + month + "\""
                + (extra == null ? "" : "," + extra) + "}";
    }

    private JsonNode run(String body) throws Exception {
        return body(mockMvc.perform(post("/v1/simulations/purchase", body)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode runContract(String body) throws Exception {
        return okBody(post("/v1/simulations/purchase", body));
    }

    private JsonNode month(JsonNode simulation, String competence) {
        for (JsonNode m : simulation.get("months")) {
            if (m.get("competence").asString().equals(competence)) {
                return m;
            }
        }
        throw new AssertionError("mês " + competence + " ausente");
    }

    private boolean hasReason(JsonNode simulation, String code) {
        for (JsonNode reason : simulation.get("reasons")) {
            if (reason.get("code").asString().equals(code)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("1.200,00 em 6x sem juros a partir de novembro: parcela 200,00; novembro traz sobra antes 2.753,25, depois = antes - 200,00 e delta -200,00; janela só de novembro (rev 3), horizon.limited e nunca SUSTAINABLE")
    void seisParcelasSemJuros() throws Exception {
        loadFixture("novembro-2026");

        JsonNode simulation = runContract(simulate("1200.00", 6, "2026-11", null));

        assertThat(money(simulation, "installmentAmount")).isEqualTo("200.00");
        assertThat(money(simulation, "total")).isEqualTo("1200.00");
        JsonNode november = month(simulation, "2026-11");
        assertThat(money(november, "surplusBefore")).isEqualTo("2753.25");
        assertThat(money(november, "surplusAfter")).isEqualTo("2553.25");
        assertThat(money(november, "delta")).isEqualTo("-200.00");
        assertThat(november.get("negativeAfter").asBoolean()).isFalse();
        assertThat(simulation.get("months")).hasSize(1);
        assertThat(simulation.get("horizon").get("requiredMonths").asInt()).isEqualTo(6);
        assertThat(simulation.get("horizon").get("calculatedMonths").asInt()).isEqualTo(1);
        assertThat(simulation.get("horizon").get("limited").asBoolean()).isTrue();
        assertThat(simulation.get("classification").asString()).isEqualTo("RISKY");
        assertThat(hasReason(simulation, "HORIZON_LIMITED")).isTrue();
    }

    @Test
    @DisplayName("compra de 100,00 à vista em novembro: SUSTAINABLE com reason NO_NEGATIVE_MONTH (um caso sintético por ramo da regra P-7)")
    void sustentavel() throws Exception {
        loadFixture("novembro-2026");

        JsonNode simulation = runContract(simulate("100.00", 1, "2026-11", null));

        assertThat(simulation.get("classification").asString()).isEqualTo("SUSTAINABLE");
        assertThat(hasReason(simulation, "NO_NEGATIVE_MONTH")).isTrue();
        assertThat(simulation.get("horizon").get("limited").asBoolean()).isFalse();
        assertThat(simulation.get("financingWarning").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("simular não grava nada: transações, postings, recorrências e perfis iguais antes e depois, e a parcela é a primeira da prévia de compra (resto de centavo na primeira)")
    void naoGravaEIgualAPrevia() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        long transactions = count("transactions");
        long postings = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);

        JsonNode simulation = run(simulate("1000.00", 3, "2026-11", null));
        JsonNode preview = body(mockMvc.perform(post("/v1/cards/" + ids.get("cartaoA") + "/purchase-preview",
                "{\"amount\":\"1000.00\",\"installments\":3,\"date\":\"2026-10-20\"}")).andExpect(status().isOk()).andReturn());

        assertThat(count("transactions")).isEqualTo(transactions);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(postings);
        assertThat(money(simulation, "installmentAmount")).isEqualTo("333.34");
        assertThat(money(preview.get("installments").get(0), "amount")).isEqualTo(money(simulation, "installmentAmount"));
    }

    @Test
    @DisplayName("com juros de 2% ao mês em 3x: a parcela e o total vêm de um cálculo único (total = soma das parcelas, acima do principal) e o mesmo valor vale na simulação repetida")
    void comJuros() throws Exception {
        loadFixture("novembro-2026");

        JsonNode simulation = run(simulate("1000.00", 3, "2026-11", "\"interestRatePerMonthBps\":200"));

        long installment = cents(simulation.get("installmentAmount"));
        long total = cents(simulation.get("total"));
        assertThat(total).isGreaterThan(100000L);
        assertThat(installment * 3).isBetween(total - 2, total + 2);
        assertThat(run(simulate("1000.00", 3, "2026-11", "\"interestRatePerMonthBps\":200")).get("installmentAmount"))
                .isEqualTo(simulation.get("installmentAmount"));
        assertThat(cents(month(simulation, "2026-11").get("delta"))).isEqualTo(-installment);
    }

    @Test
    @DisplayName("cancelSubscriptionIds subtrai o equivalente mensal das assinaturas escolhidas: USD 25,88 + semanal 22,97 = 48,85 somados à sobra depois")
    void comCortes() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");

        JsonNode simulation = runContract(simulate("1200.00", 6, "2026-11", "\"cancelSubscriptionIds\":[\"" + ids.get("assinaturaUsd") + "\",\""
                + ids.get("assinaturaSemanal") + "\"]"));

        JsonNode november = month(simulation, "2026-11");
        assertThat(money(november, "cutsMonthly")).isEqualTo("48.85");
        assertThat(cents(november.get("surplusAfterWithCuts"))).isEqualTo(cents(november.get("surplusAfter")) + 4885L);
        assertThat(simulation.get("endBalanceMinWithCuts").isNull()).isFalse();
        JsonNode without = run(simulate("1200.00", 6, "2026-11", null));
        assertThat(month(without, "2026-11").get("cutsMonthly").isNull()).isTrue();
        assertThat(month(without, "2026-11").get("surplusAfterWithCuts").isNull()).isTrue();
    }

    @Test
    @DisplayName("classificação INCOMPATIBLE: negativo mesmo com todos os cortes (NEGATIVE_EVEN_WITH_CUTS) e, sem cortes informados, NEGATIVE_WITHOUT_CUTS; cada um com reasons do ramo")
    void incompativel() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");

        JsonNode withCuts = run(simulate("50000.00", 1, "2026-11", "\"cancelSubscriptionIds\":[\"" + ids.get("assinaturaUsd") + "\"]"));
        JsonNode noCuts = run(simulate("50000.00", 1, "2026-11", null));

        assertThat(withCuts.get("classification").asString()).isEqualTo("INCOMPATIBLE");
        assertThat(hasReason(withCuts, "NEGATIVE_EVEN_WITH_CUTS")).isTrue();
        assertThat(noCuts.get("classification").asString()).isEqualTo("INCOMPATIBLE");
        assertThat(hasReason(noCuts, "NEGATIVE_WITHOUT_CUTS")).isTrue();
        assertThat(month(noCuts, "2026-11").get("negativeAfter").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("classificação VIABLE_WITH_PLANNING: negativo sem cortes e não negativo com os cortes (a compra deixa novembro em -10,40 e os cortes somam 68,85)")
    void viavelComPlanejamento() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");

        JsonNode simulation = run(simulate("2763.65", 1, "2026-11", "\"cancelSubscriptionIds\":[\"" + ids.get("assinaturaUsd") + "\",\""
                + ids.get("assinaturaSemanal") + "\",\"" + ids.get("assinaturaStreaming") + "\"]"));

        assertThat(money(month(simulation, "2026-11"), "surplusAfter")).isEqualTo("-10.40");
        assertThat(month(simulation, "2026-11").get("negativeAfterWithCuts").asBoolean()).isFalse();
        assertThat(simulation.get("classification").asString()).isEqualTo("VIABLE_WITH_PLANNING");
        assertThat(hasReason(simulation, "NEGATIVE_FIXED_WITH_CUTS")).isTrue();
    }

    @Test
    @DisplayName("classificação RISKY: sem mês negativo mas com financiamento pendente (item FINANCING não pago): financingWarning = true e reason FINANCING_PENDING")
    void arriscadoComFinanciamento() throws Exception {
        loadFixture("novembro-2026");
        createdBody(post("/v1/commitments", "{\"kind\":\"LONG_TERM_PROJECT\",\"name\":\"Casa Exemplo\",\"total\":\"300000.00\",\"schedule\":["
                + "{\"label\":\"Financiamento\",\"type\":\"FINANCING\",\"dueDate\":\"2027-06-10\",\"amount\":\"300000.00\",\"paid\":false}]}"));

        JsonNode simulation = run(simulate("100.00", 1, "2026-11", null));

        assertThat(simulation.get("financingWarning").asBoolean()).isTrue();
        assertThat(simulation.get("classification").asString()).isEqualTo("RISKY");
        assertThat(hasReason(simulation, "FINANCING_PENDING")).isTrue();
        assertThat(body(mockMvc.perform(post("/v1/cards/" + createdCardId() + "/purchase-preview", "{\"amount\":\"100.00\",\"installments\":2,\"date\":\"2026-10-20\"}"))
                .andExpect(status().isOk()).andReturn()).get("warnings").toString()).contains("FINANCING_PENDING");
    }

    private String createdCardId() {
        return jdbc.queryForObject("SELECT id::text FROM credit_cards WHERE user_id = ? LIMIT 1", String.class, userId);
    }

    @Test
    @DisplayName("horizonte limitado: 36 parcelas excedem a janela calculada, horizon.limited = true, reason HORIZON_LIMITED e nunca SUSTAINABLE")
    void horizonteLimitado() throws Exception {
        loadFixture("novembro-2026");

        JsonNode simulation = runContract(simulate("360.00", 36, "2026-11", null));

        assertThat(simulation.get("horizon").get("limited").asBoolean()).isTrue();
        assertThat(simulation.get("horizon").get("requiredMonths").asInt()).isEqualTo(36);
        assertThat(simulation.get("horizon").get("calculatedMonths").asInt()).isLessThan(36);
        assertThat(hasReason(simulation, "HORIZON_LIMITED")).isTrue();
        assertThat(simulation.get("classification").asString()).isNotEqualTo("SUSTAINABLE");
        assertThat(simulation.get("months").size()).isEqualTo(simulation.get("horizon").get("calculatedMonths").asInt());
    }

    @Test
    @DisplayName("entradas inválidas: valor zero, parcelas 0 e 37, mês inválido, juros acima de 10000, assinatura inexistente ou de outro usuário; sem token 401")
    void entradasInvalidas() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        mockMvc.perform(post("/v1/simulations/purchase", simulate("0.00", 3, "2026-11", null))).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/v1/simulations/purchase", simulate("100.00", 0, "2026-11", null))).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/v1/simulations/purchase", simulate("100.00", 37, "2026-11", null))).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/v1/simulations/purchase", simulate("100.00", 3, "2026-13", null))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/simulations/purchase", simulate("100.00", 3, "2026-11", "\"interestRatePerMonthBps\":10001"))).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/v1/simulations/purchase", simulate("100.00", 3, "2026-11", "\"cancelSubscriptionIds\":[\"" + UUID.randomUUID() + "\"]")))
                .andExpect(status().is4xxClientError());
        String other = otherUserBearer();
        mockMvc.perform(postAs(other, "/v1/simulations/purchase", simulate("100.00", 3, "2026-11", "\"cancelSubscriptionIds\":[\"" + ids.get("assinaturaUsd") + "\"]")))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/simulations/purchase")
                .contentType("application/json").content(simulate("100.00", 3, "2026-11", null))).andExpect(status().isUnauthorized());
    }
}
