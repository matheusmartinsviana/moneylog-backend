package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.card.domain.InvoiceIds;

@DisplayName("I1: campos de cartão (débito automático, bandeira, melhor dia, finais) e final por compra")
class CardProfileIT extends ResumaoItBase {

    @BeforeEach
    void mundo() throws Exception {
        basicWorld();
    }

    private String cardBody(String extra) {
        return "{\"name\":\"Cartao Perfil\",\"closingDay\":25,\"dueDay\":5,\"limit\":{\"amount\":\"1000.00\",\"currency\":\"BRL\"},\"defaultPaymentAccountId\":\""
                + accountA + "\"" + (extra == null ? "" : "," + extra) + "}";
    }

    @Test
    @DisplayName("cartão novo sem os campos: autoDebit nulo (sem alerta até o dono marcar), bandeira e melhor dia vazios, lista de finais vazia; nada muda de formato")
    void padroes() throws Exception {
        JsonNode card = createdBody(post("/v1/cards", cardBody(null)));

        assertThat(card.get("autoDebit").isNull()).isTrue();
        assertThat(card.get("brand") == null || card.get("brand").isNull()).isTrue();
        assertThat(card.get("bestPurchaseDay") == null || card.get("bestPurchaseDay").isNull()).isTrue();
        assertThat(card.get("lastDigits") == null || card.get("lastDigits").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("salva autoDebit, bandeira, melhor dia e finais e devolve; editar com versão muda o autoDebit; final com 5 dígitos ou letras é recusado")
    void salvaEEdita() throws Exception {
        JsonNode card = createdBody(post("/v1/cards", cardBody("\"autoDebit\":false,\"brand\":\"VISA\",\"bestPurchaseDay\":26,\"lastDigits\":[\"1234\",\"5678\"]")));

        assertThat(card.get("autoDebit").asBoolean()).isFalse();
        assertThat(card.get("brand").asString()).isEqualTo("VISA");
        assertThat(card.get("bestPurchaseDay").asInt()).isEqualTo(26);
        assertThat(card.get("lastDigits")).hasSize(2);
        JsonNode edited = okBody(put("/v1/cards/" + card.get("id").asString(), "{\"name\":\"Cartao Perfil\",\"closingDay\":25,\"dueDay\":5,\"autoDebit\":true,"
                + "\"lastDigits\":[\"1234\",\"5678\"],\"version\":" + card.get("version").asLong() + "}"));
        assertThat(edited.get("autoDebit").asBoolean()).isTrue();
        mockMvc.perform(post("/v1/cards", cardBody("\"lastDigits\":[\"12345\"]"))).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/v1/cards", cardBody("\"lastDigits\":[\"12ab\"]"))).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/v1/cards", cardBody("\"bestPurchaseDay\":32"))).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("compra com final que não pertence ao cartão: 422 PURCHASE_LAST_DIGITS_NOT_FROM_CARD e nada gravado; com final do cartão grava; a fatura não se divide por final")
    void finalDaCompra() throws Exception {
        String card = createdBody(post("/v1/cards", cardBody("\"lastDigits\":[\"1234\",\"5678\"]"))).get("id").asString();
        long postings = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);

        createTx("{\"type\":\"CARD_PURCHASE\",\"amount\":\"50.00\",\"date\":\"2026-10-05\",\"description\":\"Compra\",\"cardId\":\"" + card + "\",\"categoryId\":\"" + catFood
                + "\",\"installments\":1,\"lastDigits\":\"9999\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PURCHASE_LAST_DIGITS_NOT_FROM_CARD"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(postings);

        JsonNode first = created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"50.00\",\"date\":\"2026-10-05\",\"description\":\"A\",\"cardId\":\"" + card + "\",\"categoryId\":\""
                + catFood + "\",\"installments\":1,\"lastDigits\":\"1234\"}");
        created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"30.00\",\"date\":\"2026-10-06\",\"description\":\"B\",\"cardId\":\"" + card + "\",\"categoryId\":\""
                + catFood + "\",\"installments\":1,\"lastDigits\":\"5678\"}");
        assertThat(first.get("lastDigits").asString()).isEqualTo("1234");
        JsonNode invoice = okBody(get("/v1/invoices/" + InvoiceIds.of(UUID.fromString(card), LocalDate.of(2026, 10, 25))));
        assertThat(money(invoice, "total")).as("uma fatura por cartão, sem quebra por final").isEqualTo("80.00");
    }

    @Test
    @DisplayName("final de outro usuário/cartão não vale: compra no cartão sem finais cadastrados com final informado é recusada; sem final continua aceita")
    void semFinaisCadastrados() throws Exception {
        String card = createdBody(post("/v1/cards", cardBody(null))).get("id").asString();
        createTx("{\"type\":\"CARD_PURCHASE\",\"amount\":\"50.00\",\"date\":\"2026-10-05\",\"description\":\"Compra\",\"cardId\":\"" + card + "\",\"categoryId\":\"" + catFood
                + "\",\"installments\":1,\"lastDigits\":\"1234\"}").andExpect(status().isUnprocessableEntity());
        createTx("{\"type\":\"CARD_PURCHASE\",\"amount\":\"50.00\",\"date\":\"2026-10-05\",\"description\":\"Compra\",\"cardId\":\"" + card + "\",\"categoryId\":\"" + catFood
                + "\",\"installments\":1}").andExpect(status().isCreated());
    }
}
