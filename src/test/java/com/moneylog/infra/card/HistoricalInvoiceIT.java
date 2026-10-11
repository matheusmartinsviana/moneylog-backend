package com.moneylog.infra.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;

@DisplayName("Fatura histórica: registrar, pagar de verdade e não contar em dobro (ADR-014)")
class HistoricalInvoiceIT extends E4ItBase {

    private JsonNode invoice(String closing) throws Exception {
        for (JsonNode i : okBody(get("/v1/cards/" + card + "/invoices")).get("items")) {
            if (i.get("closingDate").asString().equals(closing)) {
                return i;
            }
        }
        throw new AssertionError("fatura " + closing + " não encontrada");
    }

    @Test
    @DisplayName("item de 500,00 na fatura de 25/08 (passada): 201 HISTORICAL sem categoria; fatura OVERDUE com 500,00; paga em 03/09 vira PAID; consumo 0, caixa −500,00, dívida e limite sem efeito líquido")
    void deveRegistrarEPagarFaturaHistorica() throws Exception {
        JsonNode created = body(mockMvc.perform(post("/v1/cards/" + card + "/historical-invoices",
                "{\"closingDate\":\"2026-08-25\",\"amount\":\"500.00\",\"description\":\"Fatura de agosto (pagamento anterior ao app)\"}"))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.source").value("HISTORICAL")).andReturn());
        assertThat(created.get("type").asString()).isEqualTo("CARD_PURCHASE");
        assertThat(created.get("category").isNull()).isTrue();
        JsonNode open = invoice("2026-08-25");
        assertThat(open.get("status").asString()).isEqualTo("OVERDUE");
        assertThat(money(open, "outstanding")).isEqualTo("500.00");
        assertThat(money(okBody(get("/v1/debts/overview")), "total")).isEqualTo("500.00");

        mockMvc.perform(post("/v1/invoices/" + open.get("id").asString() + "/payments",
                "{\"accountId\":\"" + nubank + "\",\"amount\":\"500.00\",\"date\":\"2026-09-03\"}").header("Idempotency-Key", java.util.UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(invoice("2026-08-25").get("status").asString()).isEqualTo("PAID");
        assertThat(money(okBody(get("/v1/debts/overview")), "total")).isEqualTo("0.00");
        assertThat(okBody(get("/v1/cards/" + card)).get("availableLimit").get("amount").asString()).isEqualTo("10000.00");
        assertThat(money(summary().get("month"), "consumption")).isEqualTo("0.00");
        assertThat(money(okBody(get("/v1/summaries/2026-08")), "expenses")).isEqualTo("0.00");
        assertThat(money(okBody(get("/v1/summaries/2026-09")), "expenses")).isEqualTo("0.00");
        assertThat(book("EXPENSE")).isZero();
        assertThat(book("CARD")).isZero();
        assertThat(book("ACCOUNT")).isEqualTo(2_000_000L - 50_000L);
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("fechamento que não é do calendário: 422 INVOICE_NOT_FOUND; fatura que ainda não fechou: 422 HISTORICAL_INVOICE_NOT_CLOSED; valor zero e corpo sem data: 4xx; outro usuário: 404")
    void deveRecusarEntradaInvalida() throws Exception {
        mockMvc.perform(post("/v1/cards/" + card + "/historical-invoices", "{\"closingDate\":\"2026-08-24\",\"amount\":\"10.00\",\"description\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND"));
        mockMvc.perform(post("/v1/cards/" + card + "/historical-invoices", "{\"closingDate\":\"2026-10-25\",\"amount\":\"10.00\",\"description\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("HISTORICAL_INVOICE_NOT_CLOSED"));
        mockMvc.perform(post("/v1/cards/" + card + "/historical-invoices", "{\"closingDate\":\"2026-08-25\",\"amount\":\"0.00\",\"description\":\"x\"}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/v1/cards/" + card + "/historical-invoices", "{\"amount\":\"10.00\",\"description\":\"x\"}")).andExpect(status().isBadRequest());
        java.util.UUID other = java.util.UUID.randomUUID();
        committed(() -> com.moneylog.util.SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@example.com"));
        mockMvc.perform(postAs(auth.bearer(other), "/v1/cards/" + card + "/historical-invoices",
                "{\"closingDate\":\"2026-08-25\",\"amount\":\"10.00\",\"description\":\"x\"}")).andExpect(status().isNotFound());
        assertThat(count("transactions")).isZero();
    }
}
