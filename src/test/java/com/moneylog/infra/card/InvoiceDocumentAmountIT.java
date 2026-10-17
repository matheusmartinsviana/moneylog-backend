package com.moneylog.infra.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.util.ContractValidation;

@DisplayName("Valor do documento da fatura: PUT /v1/invoices/{id}/document-amount (BUG-11)")
class InvoiceDocumentAmountIT extends CardItBase {

    private String url() {
        return "/v1/invoices/" + invoiceId(NOV_CLOSING) + "/document-amount";
    }

    private JsonNode change(String json) throws Exception {
        return okBody(put(url(), json));
    }

    @Test
    @DisplayName("informar o valor: a fatura devolve documentAmount, a diferença com sinal e a nova versão; o total calculado não muda")
    void informarEDivergir() throws Exception {
        twoPurchases();

        JsonNode before = invoice(NOV_CLOSING);
        assertThat(before.get("documentAmount").isNull()).isTrue();
        assertThat(before.get("documentDivergence").isNull()).isTrue();

        JsonNode changed = change("{\"documentAmount\":\"1300.00\"}");

        assertThat(money(changed, "total")).isEqualTo("1283.00");
        assertThat(money(changed, "documentAmount")).isEqualTo("1300.00");
        assertThat(money(changed, "documentDivergence")).isEqualTo("-17.00");
        assertThat(changed.get("documentVersion").asLong()).isEqualTo(1);
        JsonNode read = invoice(NOV_CLOSING);
        assertThat(money(read, "documentAmount")).isEqualTo("1300.00");
        assertThat(money(read, "documentDivergence")).isEqualTo("-17.00");
        assertThat(money(read, "total")).isEqualTo("1283.00");
    }

    @Test
    @DisplayName("valor igual ao total: sem divergência; null limpa o valor e a divergência")
    void igualELimpar() throws Exception {
        twoPurchases();

        JsonNode equal = change("{\"documentAmount\":\"1283.00\"}");
        assertThat(money(equal, "documentAmount")).isEqualTo("1283.00");
        assertThat(equal.get("documentDivergence").isNull()).isTrue();

        change("{\"documentAmount\":\"1000.00\"}");
        JsonNode cleared = change("{\"documentAmount\":null}");
        assertThat(cleared.get("documentAmount").isNull()).isTrue();
        assertThat(cleared.get("documentDivergence").isNull()).isTrue();
        assertThat(invoice(NOV_CLOSING).get("documentAmount").isNull()).isTrue();
    }

    @Test
    @DisplayName("valor negativo: 422 INVOICE_DOCUMENT_AMOUNT_INVALID e nada é gravado; formato inválido: 400")
    void negativo() throws Exception {
        twoPurchases();

        mockMvc.perform(put(url(), "{\"documentAmount\":\"-1.00\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVOICE_DOCUMENT_AMOUNT_INVALID")).andExpect(ContractValidation.conforms());
        mockMvc.perform(put(url(), "{\"documentAmount\":\"abc\"}")).andExpect(status().isBadRequest());

        assertThat(invoice(NOV_CLOSING).get("documentAmount").isNull()).isTrue();
    }

    @Test
    @DisplayName("version desatualizada: 409 VERSION_CONFLICT; a atual é aceita")
    void versao() throws Exception {
        twoPurchases();
        change("{\"documentAmount\":\"1000.00\"}");

        mockMvc.perform(put(url(), "{\"documentAmount\":\"1100.00\",\"version\":0}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        JsonNode ok = change("{\"documentAmount\":\"1100.00\",\"version\":1}");
        assertThat(ok.get("documentVersion").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("fatura de outro usuário ou inexistente: 404 INVOICE_NOT_FOUND, sem gravar")
    void faturaAlheia() throws Exception {
        twoPurchases();
        String other = auth.bearer(otherUser());

        mockMvc.perform(MockMvcRequestBuilders.put(url()).header(HttpHeaders.AUTHORIZATION, other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentAmount\":\"10.00\"}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(put("/v1/invoices/" + UUID.randomUUID() + "/document-amount", "{\"documentAmount\":\"10.00\"}"))
                .andExpect(status().isNotFound());

        assertThat(invoice(NOV_CLOSING).get("documentAmount").isNull()).isTrue();
    }

    private UUID otherUser() {
        UUID other = UUID.randomUUID();
        committed(() -> com.moneylog.util.SqlFixtures.insertUser(jdbc, other, "alheio-" + other + "@example.com"));
        return other;
    }
}
