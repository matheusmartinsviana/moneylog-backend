package com.moneylog.core.sk.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.sk.time.TimeExceptions.InvalidDayOfMonthException;

@DisplayName("DayOfMonthRule")
class DayOfMonthRuleTest {

    @Test
    @DisplayName("dia 31 em fev/2027 vira 28/02/2027 e em fev/2028 (bissexto) vira 29/02/2028")
    void deveLimitarAoUltimoDiaDoMes() {
        DayOfMonthRule dia31 = DayOfMonthRule.of(31);

        assertThat(dia31.applyTo(YearMonth.of(2027, 2))).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(dia31.applyTo(YearMonth.of(2028, 2))).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @ParameterizedTest(name = "dia {0} em {1} = {2}")
    @CsvSource({"31,2026-04,2026-04-30", "31,2026-10,2026-10-31", "30,2027-02,2027-02-28", "1,2027-02,2027-02-01", "25,2026-10,2026-10-25"})
    @DisplayName("aplica o dia ou o último dia do mês")
    void deveAplicarODia(int dia, String mes, String esperado) {
        assertThat(DayOfMonthRule.of(dia).applyTo(Competence.parse(mes))).isEqualTo(LocalDate.parse(esperado));
    }

    @ParameterizedTest(name = "rejeita dia {0}")
    @ValueSource(ints = {0, 32, -1})
    @DisplayName("rejeita dia fora de 1..31")
    void deveRejeitarDiaInvalido(int dia) {
        assertThatThrownBy(() -> DayOfMonthRule.of(dia))
                .isInstanceOf(InvalidDayOfMonthException.class)
                .extracting("code").isEqualTo("INVALID_DAY_OF_MONTH");
    }
}
