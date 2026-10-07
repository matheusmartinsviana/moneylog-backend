package com.moneylog.core.sk.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.sk.money.MoneyExceptions.UnsupportedCurrencyException;

@DisplayName("CurrencyCode")
class CurrencyCodeTest {

    @Test
    @DisplayName("aceita códigos ISO 4217 com 2 casas")
    void deveAceitarCodigosValidos() {
        assertThat(CurrencyCode.of("BRL")).isEqualTo(CurrencyCode.BRL).hasToString("BRL");
        assertThat(CurrencyCode.of("USD").value()).isEqualTo("USD");
    }

    @ParameterizedTest(name = "rejeita \"{0}\"")
    @ValueSource(strings = {"brl", "BR", "XXXX", "ZZZ", "JPY", "KWD"})
    @NullSource
    @DisplayName("rejeita código inválido, inexistente ou sem 2 casas decimais")
    void deveRejeitarCodigosInvalidos(String code) {
        assertThatThrownBy(() -> CurrencyCode.of(code))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .extracting("code").isEqualTo("CURRENCY_NOT_SUPPORTED");
    }
}
