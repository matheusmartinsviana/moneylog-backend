package com.moneylog.infra.quickentry.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.moneylog.core.quickentry.domain.QuickAmountInput;
import com.moneylog.core.quickentry.domain.QuickEntryKind;
import com.moneylog.core.transaction.domain.PaymentMethod;

@DisplayName("Leitura do campo amount pelo texto cru do JSON (M2 da revisão da T5)")
class QuickAmountJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private QuickEntryRequest read(String amountLiteral) {
        return mapper.readValue("{\"kind\":\"EXPENSE\",\"amount\":" + amountLiteral + "}", QuickEntryRequest.class);
    }

    @ParameterizedTest(name = "número {0} chega como texto cru, sem normalizar")
    @ValueSource(strings = {"27.6", "27.60", "27.600", "1e3", "2.5E1", "2.5e-1", "10e-1", "27.6e0", "35", "0.10", "-5", "1E+3", "27.605"})
    void deveEntregarOTextoCruDoNumero(String literal) {
        assertThat(read(literal).amount()).isEqualTo(new QuickAmountInput.JsonNumber(literal));
    }

    @Test
    @DisplayName("string vira Text com o conteúdo como veio")
    void deveEntregarTextoComoVeio() {
        assertThat(read("\"R$ 35,50\"").amount()).isEqualTo(new QuickAmountInput.Text("R$ 35,50"));
        assertThat(read("\"\"").amount()).isEqualTo(new QuickAmountInput.Text(""));
    }

    @ParameterizedTest(name = "{0} não é texto nem número")
    @ValueSource(strings = {"true", "false", "{}", "{\"a\":1}", "[]", "[1]"})
    void deveRecusarOQueNaoETextoNemNumero(String literal) {
        assertThatThrownBy(() -> read(literal)).isInstanceOf(JacksonException.class);
    }

    @Test
    @DisplayName("amount nulo ou ausente chega nulo para a validação de borda recusar")
    void deveDeixarNuloParaAValidacao() {
        assertThat(read("null").amount()).isNull();
        assertThat(mapper.readValue("{\"kind\":\"EXPENSE\"}", QuickEntryRequest.class).amount()).isNull();
    }

    @Test
    @DisplayName("o restante do corpo é lido como veio e a data é opcional")
    void deveLerOsDemaisCampos() {
        QuickEntryRequest request = mapper.readValue(
                "{\"kind\":\"INCOME\",\"amount\":\"1\",\"category\":\"Salário\",\"description\":\"d\",\"date\":\"2026-10-21\"}",
                QuickEntryRequest.class);

        assertThat(request.kind()).isEqualTo(QuickEntryKind.INCOME);
        assertThat(request.category()).isEqualTo("Salário");
        assertThat(request.date()).hasToString("2026-10-21");
        assertThat(request.methodOnIncome()).isFalse();
    }

    @Test
    @DisplayName("method em entrada é detectado; em gasto não")
    void deveDetectarMethodEmEntrada() {
        assertThat(mapper.readValue("{\"kind\":\"INCOME\",\"amount\":\"1\",\"method\":\"PIX\"}", QuickEntryRequest.class).methodOnIncome()).isTrue();
        QuickEntryRequest expense = mapper.readValue("{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"method\":\"PIX\"}", QuickEntryRequest.class);
        assertThat(expense.methodOnIncome()).isFalse();
        assertThat(expense.method()).isEqualTo(PaymentMethod.PIX);
    }

    @Test
    @DisplayName("tamanho do valor: vazio e mais de 40 caracteres estouram; 40 passa")
    void deveConferirOTamanhoDoValor() {
        assertThat(read("\"\"").amountOutOfSize()).isTrue();
        assertThat(read("\"" + "1".repeat(41) + "\"").amountOutOfSize()).isTrue();
        assertThat(read("\"" + "1".repeat(40) + "\"").amountOutOfSize()).isFalse();
        assertThat(read("123456789012345678901234567890123456789012").amountOutOfSize()).isTrue();
    }
}
