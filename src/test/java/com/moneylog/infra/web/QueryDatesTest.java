package com.moneylog.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.infra.exception.RequestValidationException;

@DisplayName("Datas de consulta: janela 2000-2100, faixa e no máximo 3 anos (S-239, S-240)")
class QueryDatesTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    @DisplayName("inWindow aceita 2000-01-01 a 2100-12-31 e null; recusa 1999-12-31, 2101-01-01, ano 0 e +999999999 com DATE_OUT_OF_RANGE")
    void deveValidarAJanela() {
        assertThat(QueryDates.inWindow(null, "from")).isNull();
        assertThat(QueryDates.inWindow(d("2000-01-01"), "from")).isEqualTo(d("2000-01-01"));
        assertThat(QueryDates.inWindow(d("2100-12-31"), "to")).isEqualTo(d("2100-12-31"));
        for (LocalDate bad : new LocalDate[] {d("1999-12-31"), d("2101-01-01"), LocalDate.of(0, 1, 1), LocalDate.of(999999999, 12, 31)}) {
            assertThatThrownBy(() -> QueryDates.inWindow(bad, "asOf")).isInstanceOf(RequestValidationException.class)
                    .satisfies(e -> {
                        var issue = ((RequestValidationException) e).issues().get(0);
                        assertThat(issue.field()).isEqualTo("asOf");
                        assertThat(issue.code()).isEqualTo("DATE_OUT_OF_RANGE");
                    });
        }
    }

    @Test
    @DisplayName("range: from depois de to e janela maior que 3 anos são INVALID_RANGE; exatamente 3 anos (e from = to) passam")
    void deveValidarAFaixa() {
        assertThatCode(() -> QueryDates.range(d("2026-10-10"), d("2026-10-10"))).doesNotThrowAnyException();
        assertThatCode(() -> QueryDates.range(d("2026-10-10"), d("2029-10-10"))).doesNotThrowAnyException();
        assertThatCode(() -> QueryDates.range(d("2024-02-29"), d("2027-02-28"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> QueryDates.range(d("2026-10-10"), d("2029-10-11"))).isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).issues().get(0).code()).isEqualTo("INVALID_RANGE"));
        assertThatThrownBy(() -> QueryDates.range(d("2026-10-10"), d("2026-10-09"))).isInstanceOf(RequestValidationException.class)
                .satisfies(e -> assertThat(((RequestValidationException) e).issues().get(0).field()).isEqualTo("from"));
    }
}
