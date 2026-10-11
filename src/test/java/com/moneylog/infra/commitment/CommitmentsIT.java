package com.moneylog.infra.commitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;

@DisplayName("Compromissos acompanhados (ADR-014)")
class CommitmentsIT extends E4ItBase {

    private static String item(String label, String type, String due, String amount, boolean paid) {
        return "{\"label\":\"" + label + "\",\"type\":\"" + type + "\"" + (due == null ? "" : ",\"dueDate\":\"" + due + "\"") + ",\"amount\":\"" + amount
                + "\",\"paid\":" + paid + "}";
    }

    private String project(String total) {
        return "{\"kind\":\"LONG_TERM_PROJECT\",\"name\":\"Projeto\",\"counterparty\":\"Construtora\"" + (total == null ? "" : ",\"total\":\"" + total + "\"")
                + ",\"schedule\":[" + item("Sinal", "DOWN_PAYMENT", null, "100.00", true) + "," + item("Parcela 1", "INSTALLMENT", "2026-11-15", "200.00", true) + ","
                + item("Parcela 2", "INSTALLMENT", "2026-12-15", "200.00", false) + "," + item("Reforço", "EXTRAORDINARY", "2026-12-20", "300.00", false) + ","
                + item("Financiamento", "FINANCING", null, "1000.00", false) + "]}";
    }

    @Test
    @DisplayName("projeto: 201 com derivados (pago, saldo nominal, progresso, próximo vencimento, pendente por mês); nada muda em contas, razão e total de dívidas")
    void deveCadastrarProjetoSemEfeitoNoRazao() throws Exception {
        long ledgerBefore = ledgerTotal();
        String balanceBefore = accountBalance();
        JsonNode created = createdBody(post("/v1/commitments", project("1800.00")));
        assertThat(money(created, "total")).isEqualTo("1800.00");
        assertThat(money(created, "paid")).isEqualTo("300.00");
        assertThat(money(created, "nominalBalance")).isEqualTo("1500.00");
        assertThat(created.get("progress").asString()).isEqualTo("0.1667");
        assertThat(money(created, "directPending")).isEqualTo("500.00");
        assertThat(money(created, "financingPending")).isEqualTo("1000.00");
        assertThat(created.get("nextDueDate").asString()).isEqualTo("2026-12-15");
        assertThat(created.get("pendingByMonth")).hasSize(1);
        assertThat(created.get("schedule")).hasSize(5);
        assertThat(ledgerTotal()).isEqualTo(ledgerBefore);
        assertThat(accountBalance()).isEqualTo(balanceBefore);
        assertThat(count("transactions")).isZero();

        JsonNode overview = okBody(get("/v1/debts/overview"));
        assertThat(money(overview, "total")).isEqualTo("0.00");
        assertThat(overview.get("tracked").get("items")).hasSize(1);
        assertThat(overview.get("tracked").get("scheduleByMonth")).hasSize(1);
        assertThat(okBody(get("/v1/commitments")).get("items")).hasSize(1);
        assertThat(okBody(get("/v1/commitments/" + created.get("id").asString())).get("name").asString()).isEqualTo("Projeto");
    }

    @Test
    @DisplayName("marcar item pago muda só o acompanhamento; marcar de novo 422; versão velha 409; posição inexistente 404; total que não fecha 422")
    void devePagarItemEValidar() throws Exception {
        JsonNode created = createdBody(post("/v1/commitments", project(null)));
        String id = created.get("id").asString();
        JsonNode paid = body(mockMvc.perform(post("/v1/commitments/" + id + "/schedule/3/pay", "{\"paidOn\":\"2026-12-10\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(money(paid, "paid")).isEqualTo("500.00");
        assertThat(paid.get("version").asLong()).isEqualTo(1);
        mockMvc.perform(post("/v1/commitments/" + id + "/schedule/3/pay", "{\"paidOn\":\"2026-12-10\",\"version\":1}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("COMMITMENT_SCHEDULE_ITEM_ALREADY_PAID"));
        mockMvc.perform(post("/v1/commitments/" + id + "/schedule/4/pay", "{\"paidOn\":\"2026-12-10\",\"version\":0}")).andExpect(status().isConflict());
        mockMvc.perform(post("/v1/commitments/" + id + "/schedule/99/pay", "{\"paidOn\":\"2026-12-10\",\"version\":1}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMITMENT_SCHEDULE_ITEM_NOT_FOUND"));
        mockMvc.perform(post("/v1/commitments", project("1799.99"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COMMITMENT_INVALID"));
    }

    @Test
    @DisplayName("empréstimo de terceiro: saldo e total ficam nulos (não calculados); progresso atualiza parcelas restantes; mesma Idempotency-Key devolve o mesmo")
    void deveAcompanharEmprestimoDeTerceiroSemCalcularSaldo() throws Exception {
        String key = UUID.randomUUID().toString();
        String payload = "{\"kind\":\"TRACKED_THIRD_PARTY\",\"name\":\"Empréstimo\",\"counterparty\":\"Titular\",\"installmentsTotal\":10,"
                + "\"installmentsPaid\":3,\"paidAmount\":\"300.00\",\"nextDueDate\":\"2026-11-10\"}";
        JsonNode first = body(mockMvc.perform(post("/v1/commitments", payload).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        JsonNode replay = body(mockMvc.perform(post("/v1/commitments", payload).header("Idempotency-Key", key)).andExpect(status().isCreated()).andReturn());
        assertThat(replay.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(count("tracked_commitments")).isEqualTo(1);
        assertThat(first.get("nominalBalance").isNull()).isTrue();
        assertThat(first.get("total").isNull()).isTrue();
        assertThat(first.get("remainingInstallments").asInt()).isEqualTo(7);
        assertThat(money(first, "paid")).isEqualTo("300.00");
        JsonNode updated = body(mockMvc.perform(post("/v1/commitments/" + first.get("id").asString() + "/progress",
                "{\"installmentsPaid\":4,\"paidAmount\":\"400.00\",\"nextDueDate\":\"2026-12-10\",\"version\":0}")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(updated.get("remainingInstallments").asInt()).isEqualTo(6);
        JsonNode renamed = body(mockMvc.perform(post("/v1/commitments/" + first.get("id").asString() + "/progress",
                "{\"installmentsPaid\":4,\"name\":\"Outro nome\",\"counterparty\":\"Titular a confirmar\",\"version\":1}")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(renamed.get("name").asString()).isEqualTo("Outro nome");
        assertThat(renamed.get("counterparty").asString()).isEqualTo("Titular a confirmar");
        assertThat(updated.get("nominalBalance").isNull()).isTrue();
        mockMvc.perform(post("/v1/commitments/" + first.get("id").asString() + "/schedule/1/pay", "{\"paidOn\":\"2026-12-10\",\"version\":2}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("isolamento: outro usuário não vê nem altera o compromisso (404) e a lista dele é vazia")
    void deveIsolarPorUsuario() throws Exception {
        String id = createdBody(post("/v1/commitments", project(null))).get("id").asString();
        UUID other = UUID.randomUUID();
        committed(() -> com.moneylog.util.SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com"));
        String otherBearer = auth.bearer(other);
        mockMvc.perform(getAs(otherBearer, "/v1/commitments/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(postAs(otherBearer, "/v1/commitments/" + id + "/schedule/3/pay", "{\"paidOn\":\"2026-12-10\",\"version\":0}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(getAs(otherBearer, "/v1/commitments")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }
}
