package com.moneylog.core.quickentry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.quickentry.exception.QuickEntryErrorCodes;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickAmountAmbiguousException;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickAmountInvalidException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

@DisplayName("Parser do valor digitado no Atalho (ADR-034, seção 6)")
class AmountTextTest {

    @ParameterizedTest(name = "\"{0}\" vira {1}")
    @CsvSource(delimiter = '|', value = {
            "35|35.00", "35,5|35.50", "35,50|35.50", "1,5|1.50",
            "1.234,56|1234.56", "1.234.567,89|1234567.89", "R$ 35,00|35.00", "R$35,00|35.00", "r$ 35,00|35.00",
            "35.50|35.50", "35.5|35.50", "0,01|0.01", "0.99|0.99", " 1 234,56 |1234.56", "007,50|7.50",
            "999.999.999,99|999999999.99", "999999999.99|999999999.99", "100|100.00", "2500,00|2500.00"})
    void deveInterpretarOsFormatosAceitos(String input, String expected) {
        assertThat(AmountText.parseText(input)).isEqualTo(Money.of(expected));
    }

    @ParameterizedTest(name = "\"{0}\" é ambíguo")
    @ValueSource(strings = {"1.234", "35.500", "1.234.567", "0.500", "R$ 1.000"})
    void deveRecusarOAmbiguoPedindoVirgula(String input) {
        assertThatThrownBy(() -> AmountText.parseText(input)).isInstanceOf(QuickAmountAmbiguousException.class)
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_AMOUNT_AMBIGUOUS)
                .hasMessageContaining("vírgula");
    }

    @ParameterizedTest(name = "\"{0}\" é inválido")
    @ValueSource(strings = {"10,005", "1,234,56", "12.34,56", "0", "0,00", "0.00", "-5", "-5,00", "", "   ", "abc", "R$", "35,", ",5", ".5", "35.",
            "1.2.3", "1.2345", "1234.567", "35,5,5", "35a", "3 5a", "1e3", "35,50,", "999.999.999,995", "1.000.000.000,00", "1000000000", "9999999999999999999999",
            "1.23,45", "١٢٣", "1..234,00", "R$ R$ 5"})
    void deveRecusarOInvalido(String input) {
        assertThatThrownBy(() -> AmountText.parseText(input)).isInstanceOf(QuickAmountInvalidException.class)
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_AMOUNT_INVALID);
    }

    @Test
    @DisplayName("nulo é inválido")
    void deveRecusarNulo() {
        assertThatThrownBy(() -> AmountText.parseText(null)).isInstanceOf(QuickAmountInvalidException.class);
        assertThatThrownBy(() -> AmountText.parseJsonNumber(null)).isInstanceOf(QuickAmountInvalidException.class);
    }

    @ParameterizedTest(name = "número JSON {0} vira {1}")
    @CsvSource({"27.6,27.60", "27.60,27.60", "35,35.00", "1234.56,1234.56", "35.500,35.50", "0.01,0.01", "27.600,27.60", "999999999.99,999999999.99"})
    void deveLerONumeroJsonExato(String raw, String expected) {
        assertThat(AmountText.parseJsonNumber(raw)).isEqualTo(Money.of(expected));
    }

    @ParameterizedTest(name = "número JSON {0} é inválido")
    @ValueSource(strings = {"27.605", "0.001", "0", "0.0", "0.00", "-5", "-0.5", "1e3", "2.5E1", "1E+3", "27.", ".5", "+5", "1,5", "NaN", "1.234", "1000000000"})
    void deveRecusarNumeroJsonInvalido(String raw) {
        assertThatThrownBy(() -> AmountText.parseJsonNumber(raw)).isInstanceOf(QuickAmountInvalidException.class);
    }

    @Test
    @DisplayName("o artefato de ponto flutuante 27.600000000000001 é recusado em vez de arredondado")
    void deveRecusarArtefatoDeDouble() {
        assertThatThrownBy(() -> AmountText.parseJsonNumber("27.600000000000001")).isInstanceOf(QuickAmountInvalidException.class);
    }

    @Test
    @DisplayName("o limite técnico é o de POST /v1/transactions: o teto passa e um centavo acima é recusado")
    void deveRespeitarOLimiteTecnico() {
        assertThat(AmountText.parseText("999.999.999,99").cents()).isEqualTo(AmountLimit.MAX_CENTS);
        assertThatThrownBy(() -> AmountText.parseText("1.000.000.000,00")).isInstanceOf(QuickAmountInvalidException.class);
    }

    @Test
    @DisplayName("propriedade: todo centavo formatado em pt-BR e lido de volta é o mesmo valor (texto e número)")
    void deveVoltarIgualEmIdaEVolta() {
        Random random = new Random(20261021L);
        for (int i = 0; i < 20_000; i++) {
            long cents = random.nextLong(1, AmountLimit.MAX_CENTS + 1);
            Money original = Money.ofCents(cents, CurrencyCode.BRL);
            assertThat(AmountText.parseText(AmountText.format(original))).isEqualTo(original);
            assertThat(AmountText.parseText(AmountText.formatBrl(original))).isEqualTo(original);
            assertThat(AmountText.parseJsonNumber(original.toPlainString())).isEqualTo(original);
        }
        for (long cents = 1; cents <= 5_000; cents++) {
            Money original = Money.ofCents(cents, CurrencyCode.BRL);
            assertThat(AmountText.parseText(AmountText.format(original))).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("formata com milhar em ponto e centavos em vírgula")
    void deveFormatar() {
        assertThat(AmountText.formatBrl(Money.of("35.50"))).isEqualTo("R$ 35,50");
        assertThat(AmountText.formatBrl(Money.of("2500.00"))).isEqualTo("R$ 2.500,00");
        assertThat(AmountText.formatBrl(Money.of("1234567.89"))).isEqualTo("R$ 1.234.567,89");
        assertThat(AmountText.formatBrl(Money.of("0.05"))).isEqualTo("R$ 0,05");
        assertThat(AmountText.formatBrl(Money.of("100.00"))).isEqualTo("R$ 100,00");
    }
}
