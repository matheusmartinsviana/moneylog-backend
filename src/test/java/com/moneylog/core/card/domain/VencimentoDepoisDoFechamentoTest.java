package com.moneylog.core.card.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.moneylog.core.card.util.factory.CreditCardTestFactory;

@DisplayName("Vencimento estritamente depois do fechamento (S-77)")
class VencimentoDepoisDoFechamentoTest {

    @ParameterizedTest(name = "fecha {0}, vence {1} em {2}: fecha {3}, vence {4}")
    @CsvSource({
            "28, 30, 2027-02, 2027-02-28, 2027-03-30",
            "30, 31, 2027-02, 2027-02-28, 2027-03-31",
            "29, 30, 2027-02, 2027-02-28, 2027-03-30",
            "29, 30, 2028-02, 2028-02-29, 2028-03-30",
            "30, 31, 2027-04, 2027-04-30, 2027-05-31",
            "30, 31, 2027-06, 2027-06-30, 2027-07-31",
            "31, 31, 2027-02, 2027-02-28, 2027-03-31",
            "30, 31, 2027-01, 2027-01-30, 2027-01-31",
            "25, 5, 2026-10, 2026-10-25, 2026-11-05",
    })
    @DisplayName("combinações em que o clamp igualaria as datas empurram o vencimento para o mês seguinte")
    void deveEmpurrarOVencimento(int closing, int due, String month, String expectedClosing, String expectedDue) {
        InvoicePeriod p = CreditCardTestFactory.umCartao(closing, due).calendar().periodClosingIn(YearMonth.parse(month));
        assertThat(p.closingDate()).isEqualTo(LocalDate.parse(expectedClosing));
        assertThat(p.dueDate()).isEqualTo(LocalDate.parse(expectedDue));
        assertThat(p.dueDate()).isAfter(p.closingDate());
        assertThat(p.referenceMonth()).isEqualTo(YearMonth.from(p.dueDate()));
    }

    @Test
    @DisplayName("propriedade: para todo par de dias 1..31 e todos os meses de 2026 a 2029, vencimento > fechamento")
    void deveSerEstritamenteDepoisParaTodosOsPares() {
        for (int closing = 1; closing <= 31; closing++) {
            for (int due = 1; due <= 31; due++) {
                InvoiceCalendar calendar = CreditCardTestFactory.umCartao(closing, due).calendar();
                for (YearMonth m = YearMonth.of(2026, 1); !m.isAfter(YearMonth.of(2029, 12)); m = m.plusMonths(1)) {
                    InvoicePeriod p = calendar.periodClosingIn(m);
                    assertThat(p.dueDate()).as(closing + "/" + due + " " + m).isAfter(p.closingDate());
                }
            }
        }
    }

    @Test
    @DisplayName("isClosingDate: só o fechamento exato do mês é fechamento")
    void deveReconhecerSoOFechamento() {
        InvoiceCalendar calendar = CreditCardTestFactory.umCartao(31, 10).calendar();
        assertThat(calendar.isClosingDate(LocalDate.of(2027, 2, 28))).isTrue();
        assertThat(calendar.isClosingDate(LocalDate.of(2027, 2, 27))).isFalse();
        assertThat(calendar.isClosingDate(LocalDate.of(2027, 3, 31))).isTrue();
    }
}
