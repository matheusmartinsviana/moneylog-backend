package com.moneylog.infra.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.infra.debt.E4ItBase;

/**
 * Carga de fatura já emitida (ADR-014): fatura fechada, ainda no prazo de vencimento e sem nenhum item recebe a compra do ciclo como lançamento
 * tardio; depois do vencimento volta a regra geral (fatura sem movimento é paga: a compra vai para a corrente). Cartão fecha dia 25, vence dia 5.
 */
@DisplayName("Fatura emitida e sem itens recebe a compra do ciclo dentro do prazo (ADR-014)")
class IssuedInvoiceLoadIT extends E4ItBase {

    private JsonNode purchase(String date, String amount) throws Exception {
        return created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Compra\",\"cardId\":\""
                + card + "\",\"categoryId\":\"" + food + "\",\"installments\":1}");
    }

    private JsonNode invoice(String closing) throws Exception {
        JsonNode list = okBody(get("/v1/cards/" + card + "/invoices"));
        for (JsonNode i : list.get("items")) {
            if (i.get("closingDate").asString().equals(closing)) {
                return i;
            }
        }
        throw new AssertionError("fatura " + closing + " não encontrada em " + list);
    }

    @Test
    @DisplayName("em 27/10 (fechada, vence 05/11, vazia) a compra de 10/10 entra na fatura que fechou em 25/10, que fica CLOSED com o valor; ela não vai para a corrente")
    void deveCarregarNaFaturaEmitidaDentroDoPrazo() throws Exception {
        advanceTo(OCT_27);
        purchase("2026-10-10", "120.00");
        JsonNode closed = invoice("2026-10-25");
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(money(closed, "total")).isEqualTo("120.00");
        assertThat(count("transactions")).isEqualTo(1);
        // segunda compra do mesmo ciclo: a fatura já tem item, segue o mesmo caminho
        purchase("2026-10-12", "30.00");
        assertThat(money(invoice("2026-10-25"), "total")).isEqualTo("150.00");
    }

    @Test
    @DisplayName("depois do vencimento (06/11) a fatura vazia vale como paga: a compra do ciclo antigo vai para a fatura corrente (regra geral)")
    void deveManterARegraGeralDepoisDoVencimento() throws Exception {
        advanceTo(Instant.parse("2026-11-06T15:00:00Z"));
        purchase("2026-10-10", "120.00");
        JsonNode list = okBody(get("/v1/cards/" + card + "/invoices"));
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("items").get(0).get("closingDate").asString()).isEqualTo("2026-11-25");
    }

    @Test
    @DisplayName("fechamento não confirmado: cartão novo nasce confirmado; marcar false aparece no cartão e não muda o calendário nem as faturas; versão velha é 409")
    void deveMarcarFechamentoNaoConfirmado() throws Exception {
        JsonNode before = okBody(get("/v1/cards/" + card));
        assertThat(before.get("closingConfirmed").asBoolean()).isTrue();
        JsonNode marked = body(mockMvc.perform(post("/v1/cards/" + card + "/closing-confirmation", "{\"confirmed\":false,\"version\":" + before.get("version").asLong() + "}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(com.moneylog.util.ContractValidation.conforms()).andReturn());
        assertThat(marked.get("closingConfirmed").asBoolean()).isFalse();
        assertThat(marked.get("closingDay").asInt()).isEqualTo(before.get("closingDay").asInt());
        assertThat(okBody(get("/v1/cards/" + card)).get("closingConfirmed").asBoolean()).isFalse();
        mockMvc.perform(post("/v1/cards/" + card + "/closing-confirmation", "{\"confirmed\":true,\"version\":" + before.get("version").asLong() + "}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
    }
}
