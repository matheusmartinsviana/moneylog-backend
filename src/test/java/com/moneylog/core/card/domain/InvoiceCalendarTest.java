package com.moneylog.core.card.domain;

import static com.moneylog.core.card.util.factory.CreditCardTestFactory.umCartao;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("InvoiceCalendar (T5)")
class InvoiceCalendarTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @ParameterizedTest(name = "fecha {0}/vence {1}, compra {2} → fecha {3}, vence {4}, ref {5}, início {6}")
    @CsvSource({
            // CA1: fechamento 25, vencimento 5 (S-12: no dia do fechamento vai para a seguinte)
            "25, 5, 2026-10-24, 2026-10-25, 2026-11-05, 2026-11, 2026-09-25",
            "25, 5, 2026-10-25, 2026-11-25, 2026-12-05, 2026-12, 2026-10-25",
            "25, 5, 2026-10-26, 2026-11-25, 2026-12-05, 2026-12, 2026-10-25",
            "25, 5, 2026-10-10, 2026-10-25, 2026-11-05, 2026-11, 2026-09-25",
            // CA2: vencimento > fechamento: mesmo mês
            "3, 10, 2027-03-01, 2027-03-03, 2027-03-10, 2027-03, 2027-02-03",
            // CA3: fechamento 31 em fevereiro; virada de ano
            "31, 5, 2027-02-15, 2027-02-28, 2027-03-05, 2027-03, 2027-01-31",
            "25, 5, 2026-12-20, 2026-12-25, 2027-01-05, 2027-01, 2026-11-25",
            "31, 10, 2028-02-29, 2028-03-31, 2028-04-10, 2028-04, 2028-02-29"
    })
    @DisplayName("CA1–CA3: fatura de uma compra pelo calendário")
    void deveCalcularAFatura(int closing, int due, String purchase, String expectedClosing, String expectedDue, String reference,
                             String start) {
        InvoicePeriod period = umCartao(closing, due).calendar().periodFor(d(purchase));

        assertThat(period.closingDate()).isEqualTo(d(expectedClosing));
        assertThat(period.dueDate()).isEqualTo(d(expectedDue));
        assertThat(period.referenceMonth()).isEqualTo(YearMonth.parse(reference));
        assertThat(period.periodStart()).isEqualTo(d(start));
        assertThat(period.contains(d(purchase))).isTrue();
    }

    @Test
    @DisplayName("CA1: período da fatura que fecha em 25/10/2026 = 25/09 a 24/10")
    void deveCalcularOPeriodo() {
        InvoicePeriod period = umCartao().calendar().periodFor(d("2026-10-24"));
        assertThat(period.periodStart()).isEqualTo(d("2026-09-25"));
        assertThat(period.periodEnd()).isEqualTo(d("2026-10-24"));
        assertThat(period.contains(d("2026-10-25"))).isFalse();
        assertThat(period.contains(d("2026-09-24"))).isFalse();
    }

    @Test
    @DisplayName("CA9: vencimento muda de 5 para 28 em 15/10: a corrente mantém 25/10–05/11 e a seguinte fecha 25/11 e vence 28/11 (ref. nov também)")
    void deveMudarOVencimentoDaFaturaSeguinte() {
        CreditCard card = umCartao();
        card.changeBillingDays(25, 28, d("2026-10-15"));
        InvoiceCalendar calendar = card.calendar();

        InvoicePeriod corrente = calendar.periodFor(d("2026-10-15"));
        InvoicePeriod seguinte = calendar.next(corrente);
        assertThat(corrente.closingDate()).isEqualTo(d("2026-10-25"));
        assertThat(corrente.dueDate()).isEqualTo(d("2026-11-05"));
        assertThat(corrente.referenceMonth()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(seguinte.closingDate()).isEqualTo(d("2026-11-25"));
        assertThat(seguinte.dueDate()).isEqualTo(d("2026-11-28"));
        assertThat(seguinte.referenceMonth()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(card.dueDay()).isEqualTo(28);
        assertFechamentosCrescentes(calendar, corrente);
    }

    @Test
    @DisplayName("CA9: fechamento muda de 25 para 10 em 15/10: a corrente mantém 25/10 e a seguinte fecha em 10/11 e vence em 05/12")
    void deveMudarOFechamentoNoMesSeguinte() {
        CreditCard card = umCartao();
        card.changeBillingDays(10, 5, d("2026-10-15"));
        InvoiceCalendar calendar = card.calendar();

        InvoicePeriod corrente = calendar.periodFor(d("2026-10-15"));
        InvoicePeriod seguinte = calendar.next(corrente);
        assertThat(corrente.closingDate()).isEqualTo(d("2026-10-25"));
        assertThat(seguinte.closingDate()).isEqualTo(d("2026-11-10"));
        assertThat(seguinte.periodStart()).isEqualTo(d("2026-10-25"));
        assertThat(seguinte.dueDate()).isEqualTo(d("2026-12-05"));
        assertThat(calendar.periodFor(d("2026-11-10")).closingDate()).isEqualTo(d("2026-12-10"));
        assertThat(card.closingDay()).isEqualTo(10);
        assertFechamentosCrescentes(calendar, corrente);
    }

    @Test
    @DisplayName("uma segunda mudança antes de a primeira valer substitui a regra pendente; regras antigas continuam valendo para o passado")
    void deveSubstituirRegraPendente() {
        CreditCard card = umCartao();
        card.changeBillingDays(10, 5, d("2026-10-15"));
        card.changeBillingDays(20, 1, d("2026-10-20"));

        assertThat(card.billingRules()).hasSize(2);
        assertThat(card.calendar().periodClosingIn(YearMonth.of(2026, 11)).closingDate()).isEqualTo(d("2026-11-20"));
        assertThat(card.calendar().periodClosingIn(YearMonth.of(2026, 1)).closingDate()).isEqualTo(d("2026-01-25"));
    }

    private static void assertFechamentosCrescentes(InvoiceCalendar calendar, InvoicePeriod from) {
        List<LocalDate> closings = new ArrayList<>();
        InvoicePeriod p = calendar.plusCycles(from, -3);
        for (int i = 0; i < 18; i++) {
            closings.add(p.closingDate());
            p = calendar.next(p);
        }
        for (int i = 1; i < closings.size(); i++) {
            assertThat(closings.get(i)).isAfter(closings.get(i - 1));
            assertThat(YearMonth.from(closings.get(i))).isEqualTo(YearMonth.from(closings.get(i - 1)).plusMonths(1));
        }
    }
}
