package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.util.ContractValidation;

import tools.jackson.databind.JsonNode;

@DisplayName("Mês previsto do recebimento, editável por recebível (ADR-023)")
class ReceivableExpectedMonthIT extends ResumaoItBase {

    private String url(String tx, String installment, String person) {
        return "/v1/third-party-receivables/" + tx + "/installments/" + installment + "/people/" + person + "/expected-month";
    }

    private JsonNode installmentsOf(String tx, String person) throws Exception {
        for (JsonNode p : okBody(get("/v1/debts/third-party-receivables")).get("people")) {
            if (p.get("personId").asString().equals(person)) {
                for (JsonNode item : p.get("items")) {
                    if (item.get("refId").asString().equals(tx)) {
                        return item.get("installments");
                    }
                }
            }
        }
        throw new AssertionError("item não encontrado");
    }

    private String receivablesOf(String from) throws Exception {
        return money(okBody(get("/v1/owner-statement?from=" + from + "&months=1")).get("months").get(0), "receivablesPending");
    }

    @Test
    @DisplayName("editar a parcela 1 da Ana (jantar) para dezembro: novembro 66,66 e dezembro 100,00 (a parcela 2 que já vencia em dezembro + a movida); a resposta traz a parcela com o mês efetivo")
    void editaEMoveSoAquelaParcela() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        String tx = ids.get("compraMetade");
        JsonNode installments = installmentsOf(tx, ids.get("ana"));
        assertThat(installments).hasSize(3);
        assertThat(installments.get(0).get("expectedMonth").asString()).isEqualTo("2026-11");
        assertThat(installments.get(0).get("overridden").asBoolean()).isFalse();
        assertThat(receivablesOf("2026-11")).isEqualTo("116.66");
        assertThat(receivablesOf("2026-12")).isEqualTo("50.00");

        JsonNode response = body(mockMvc.perform(put(url(tx, installments.get(0).get("installmentId").asString(), ids.get("ana")),
                "{\"expectedMonth\":\"2026-12\"}")).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());

        assertThat(response.get("installment").get("expectedMonth").asString()).isEqualTo("2026-12");
        assertThat(response.get("installment").get("dueMonth").asString()).isEqualTo("2026-11");
        assertThat(response.get("installment").get("overridden").asBoolean()).isTrue();
        assertThat(response.get("version").asLong()).isZero();
        assertThat(receivablesOf("2026-11")).isEqualTo("66.66");
        assertThat(receivablesOf("2026-12")).isEqualTo("100.00");
        JsonNode november = okBody(get("/v1/owner-statement?from=2026-11&months=1")).get("months").get(0);
        assertThat(money(november.get("income"), "total")).isEqualTo("5742.77");
        assertThat(cents(november.get("endBalance"))).isEqualTo(900000L + cents(november.get("surplus")));
    }

    @Test
    @DisplayName("vale o último valor: reeditar muda o mês, a versão sobe, e mês nulo volta ao vencimento da fatura")
    void ultimoValorEReset() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        String tx = ids.get("compraMetade");
        String installment = installmentsOf(tx, ids.get("ana")).get(0).get("installmentId").asString();
        mockMvc.perform(put(url(tx, installment, ids.get("ana")), "{\"expectedMonth\":\"2026-12\"}")).andExpect(status().isOk());

        JsonNode second = body(mockMvc.perform(put(url(tx, installment, ids.get("ana")), "{\"expectedMonth\":\"2027-03\",\"version\":0}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(second.get("version").asLong()).isEqualTo(1L);
        assertThat(receivablesOf("2026-12")).isEqualTo("50.00");
        assertThat(receivablesOf("2027-03")).isEqualTo("50.00");

        mockMvc.perform(put(url(tx, installment, ids.get("ana")), "{\"expectedMonth\":\"2027-04\",\"version\":0}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        JsonNode reset = body(mockMvc.perform(put(url(tx, installment, ids.get("ana")), "{\"expectedMonth\":null}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(reset.get("installment").get("overridden").asBoolean()).isFalse();
        assertThat(reset.get("installment").get("expectedMonth").asString()).isEqualTo("2026-11");
        assertThat(receivablesOf("2026-11")).isEqualTo("116.66");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receivable_expected_months WHERE user_id = ?", Long.class, userId)).isZero();
    }

    @Test
    @DisplayName("formato inválido 400; fora de 2000-01..2100-12 422 RECEIVABLE_EXPECTED_MONTH_INVALID; sem token 401")
    void mesInvalido() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        String tx = ids.get("compraMetade");
        String installment = installmentsOf(tx, ids.get("ana")).get(0).get("installmentId").asString();

        mockMvc.perform(put(url(tx, installment, ids.get("ana")), "{\"expectedMonth\":\"dezembro\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(put(url(tx, installment, ids.get("ana")), "{\"expectedMonth\":\"2026-13\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(put(url(tx, installment, ids.get("ana")), "{\"expectedMonth\":\"1999-12\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECEIVABLE_EXPECTED_MONTH_INVALID"));
        mockMvc.perform(put(url(tx, installment, ids.get("ana")), "{\"expectedMonth\":\"2101-01\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECEIVABLE_EXPECTED_MONTH_INVALID"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url(tx, installment, ids.get("ana")))
                .contentType("application/json").content("{\"expectedMonth\":\"2026-12\"}")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receivable_expected_months WHERE user_id = ?", Long.class, userId)).isZero();
    }

    @Test
    @DisplayName("recebível de outro usuário ou inexistente: 404 RECEIVABLE_NOT_FOUND e nada gravado")
    void isolamentoPorUsuario() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        String tx = ids.get("compraMetade");
        String installment = installmentsOf(tx, ids.get("ana")).get(0).get("installmentId").asString();

        mockMvc.perform(putAs(otherUserBearer(), url(tx, installment, ids.get("ana")), "{\"expectedMonth\":\"2026-12\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECEIVABLE_NOT_FOUND"));
        mockMvc.perform(put(url(tx, java.util.UUID.randomUUID().toString(), ids.get("ana")), "{\"expectedMonth\":\"2026-12\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECEIVABLE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receivable_expected_months WHERE user_id = ?", Long.class, userId)).isZero();
    }

    @Test
    @DisplayName("parcela já recebida por inteiro: 422 RECEIVABLE_ALREADY_RECEIVED; a parcela seguinte segue editável")
    void jaRecebido() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        String tx = ids.get("compraMetade");
        JsonNode installments = installmentsOf(tx, ids.get("ana"));
        mockMvc.perform(idem(post("/v1/third-party-receipts", "{\"personId\":\"" + ids.get("ana") + "\",\"transactionId\":\"" + tx
                + "\",\"amount\":\"50.00\",\"receivedOn\":\"2026-10-15\"}"))).andExpect(status().isCreated());

        mockMvc.perform(put(url(tx, installments.get(0).get("installmentId").asString(), ids.get("ana")), "{\"expectedMonth\":\"2026-12\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECEIVABLE_ALREADY_RECEIVED"));
        mockMvc.perform(put(url(tx, installments.get(1).get("installmentId").asString(), ids.get("ana")), "{\"expectedMonth\":\"2027-01\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM receivable_expected_months WHERE user_id = ?", Long.class, userId)).isEqualTo(1L);
    }
}
