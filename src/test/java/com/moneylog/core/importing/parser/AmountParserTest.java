package com.moneylog.core.importing.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AmountParser: centavos exatos, sem double")
class AmountParserTest {

    @ParameterizedTest(name = "\"{0}\" = {1} centavos")
    @CsvSource(delimiter = '|', value = {
            "1.234,56|123456", "1,234.56|123456", "-82,90|-8290", "(82,90)|-8290", "R$ 82,90|8290", "82.90|8290", "1234|123400",
            "0,05|5", "0.5|50", "-1.234.567,89|-123456789", "82,90-|-8290", "+10,00|1000", "R$ -5,00|-500", "1.234|123400", "82,90 D|-8290", "82,90 C|8290"})
    @DisplayName("formatos de valor que os bancos exportam")
    void formatos(String text, long cents) {
        assertThat(AmountParser.parseCents(text)).contains(cents);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "  ", "1,2,3", "12,345,67", "1.23.45", "1,234.567", "--5", "R$", "1e5", "NaN", "9999999999999999999,99", "82,"})
    @DisplayName("o que não é valor vira vazio (a linha será ignorada e listada)")
    void invalidos(String text) {
        assertThat(AmountParser.parseCents(text)).isEmpty();
    }

    @Test
    @DisplayName("nulo é vazio")
    void nulo() {
        assertThat(AmountParser.parseCents(null)).isEmpty();
    }

    @Test
    @DisplayName("propriedade: render(centavos) em pt-BR e en-US volta exatamente os mesmos centavos (nenhum centavo a mais ou a menos)")
    void roundTrip() {
        Random random = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            long cents = (long) (random.nextDouble() * 1_000_000_000L) * (random.nextBoolean() ? 1 : -1);
            BigDecimal value = BigDecimal.valueOf(cents, 2);
            String plain = value.toPlainString();
            String br = String.format(Locale.of("pt", "BR"), "%,.2f", value);
            String us = String.format(Locale.US, "%,.2f", value);
            assertThat(AmountParser.parseCents(plain)).as(plain).contains(cents);
            assertThat(AmountParser.parseCents(br)).as(br).contains(cents);
            assertThat(AmountParser.parseCents(us)).as(us).contains(cents);
        }
    }
}
