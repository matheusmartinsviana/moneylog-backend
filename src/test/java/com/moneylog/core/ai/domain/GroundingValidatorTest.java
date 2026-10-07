package com.moneylog.core.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GroundingValidatorTest {

    private final AiFacts facts = new AiFacts(Map.of("month", "2026-10", "income", "3000.00", "expenses", "1283.00",
            "expensesVsPreviousPercent", "23.00", "cardOutstanding", "2400.00", "largestCategoryName", "ALIMENTACAO"));

    @ParameterizedTest
    @ValueSource(strings = {
            "Você gastou R$ 1.283,00 em outubro de 2026.",
            "Despesas de 1283.00 no mês.",
            "Despesas de 1283 no mês.",
            "A alimentação subiu 23% em relação ao mês anterior.",
            "Subiu 23,0% no mês.",
            "A fatura do cartão soma R$ 2.400,00.",
            "Receitas: R$ 3.000,00; sem outros números além de 2026-10.",
            "Nada numérico aqui.",
            "Gastou mil duzentos e oitenta e três reais.",
            "Cartão: 2,400.00 USD style.",
            "Mês 10 de 2026.",
            "R$3000"
    })
    void deveAceitarTextoComNumerosDosFatos(String text) {
        assertThat(GroundingValidator.isGrounded(text, List.of(), facts)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Você gastou R$ 1.284,00.",
            "Subiu 24%.",
            "Receitas de R$ 3.001,00.",
            "Economizou 500 reais.",
            "Fatura de 2.401,00.",
            "Gastou 12.83 no mês.",
            "Saldo de 999999.",
            "Terá 7 parcelas.",
            "Sobrou R$ 1.717,00.",
            "Subiu 230%.",
            "Gastou 128,30.",
            "Em 2027 será pior."
    })
    void deveRejeitarNumeroForaDosFatos(String text) {
        assertThat(GroundingValidator.isGrounded(text, List.of(), facts)).as(text).isFalse();
        assertThat(GroundingValidator.ungroundedReason(text, List.of(), facts)).isEqualTo("NUMBER_NOT_IN_FACTS");
    }

    @Test
    void deveRejeitarReferenciaDeFatoInexistente() {
        assertThat(GroundingValidator.ungroundedReason("ok", List.of("income", "inexistente"), facts)).isEqualTo("UNKNOWN_FACT_REF");
        assertThat(GroundingValidator.isGrounded("ok", List.of("income", "expenses"), facts)).isTrue();
    }

    @Test
    void deveRejeitarTextoVazio() {
        assertThat(GroundingValidator.ungroundedReason("  ", List.of(), facts)).isEqualTo("EMPTY_TEXT");
        assertThat(GroundingValidator.ungroundedReason(null, List.of(), facts)).isEqualTo("EMPTY_TEXT");
    }

    @Test
    void deveConferirCategoriaPermitida() {
        assertThat(GroundingValidator.isAllowedCategory("Mercado", List.of("Mercado", "Lazer"))).isTrue();
        assertThat(GroundingValidator.isAllowedCategory("Inventada", List.of("Mercado", "Lazer"))).isFalse();
        assertThat(GroundingValidator.isAllowedCategory(null, List.of("Mercado"))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"'1.283,00',1283", "'1283.00',1283", "'23%',23", "'0.5',0.5", "'0,5',0.5", "'1,283',1283", "'12.345.678,90',12345678.9", "'3000.00',3000"})
    void deveNormalizarNumeros(String token, String expected) {
        String raw = token.replace("%", "");
        assertThat(GroundingValidator.normalize(raw)).isEqualTo(expected);
    }
}
