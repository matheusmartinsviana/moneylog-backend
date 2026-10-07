package com.moneylog.core.sk.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.sk.time.TimeExceptions.InvalidCompetenceException;

@DisplayName("Competence")
class CompetenceTest {

    @Test
    @DisplayName("2026-12 + 1 mês = 2027-01 (virada de ano)")
    void deveVirarOAno() {
        assertThat(Competence.of(2026, 12).plusMonths(1)).isEqualTo(Competence.of(2027, 1));
        assertThat(Competence.of(2027, 1).minusMonths(1)).isEqualTo(Competence.of(2026, 12));
    }

    @Test
    @DisplayName("vem da data e é formatada como AAAA-MM")
    void deveVirDaDataEFormatar() {
        Competence outubro = Competence.from(LocalDate.of(2026, 10, 10));

        assertThat(outubro).hasToString("2026-10");
        assertThat(Competence.parse("2026-10")).isEqualTo(outubro);
        assertThat(outubro.firstDay()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(outubro.lastDay()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(outubro.contains(LocalDate.of(2026, 10, 31))).isTrue();
        assertThat(outubro.contains(LocalDate.of(2026, 11, 1))).isFalse();
    }

    @Test
    @DisplayName("ordena por mês")
    void deveOrdenar() {
        Competence out = Competence.of(2026, 10);
        Competence nov = Competence.of(2026, 11);

        assertThat(out.isBefore(nov)).isTrue();
        assertThat(nov.isAfter(out)).isTrue();
        assertThat(out.compareTo(nov)).isNegative();
    }

    @Test
    @DisplayName("rejeita mês inválido e texto fora do formato")
    void deveRejeitarInvalidos() {
        assertThatThrownBy(() -> Competence.of(2026, 13)).isInstanceOf(InvalidCompetenceException.class);
        assertThatThrownBy(() -> Competence.parse("10/2026"))
                .isInstanceOf(InvalidCompetenceException.class)
                .extracting("code").isEqualTo("INVALID_COMPETENCE");
    }
}
