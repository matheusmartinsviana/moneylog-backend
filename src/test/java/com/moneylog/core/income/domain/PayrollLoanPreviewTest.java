package com.moneylog.core.income.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileInvalidException;
import com.moneylog.core.sk.money.Money;

@DisplayName("Rubrica LOAN: juros opcionais e aviso quando não há divisão")
class PayrollLoanPreviewTest {

    private static final UUID DEBT = UUID.fromString("00000000-0000-0000-0000-0000000d0b09");

    private static PayrollDeduction loan(String interest) {
        return new PayrollDeduction(DeductionKind.LOAN, "Consignado", Money.of("300.00"), null, LocalDate.of(2026, 1, 1), null, DEBT,
                interest == null ? null : Money.of(interest));
    }

    @Test
    @DisplayName("sem juros informados a parcela inteira é principal e o aviso aparece")
    void withoutInterestWarns() {
        IncomePreview.Deduction line = new IncomePreview.Deduction(DeductionKind.LOAN, "Consignado", Money.of("300.00"), DEBT, null,
                Money.of("300.00"), java.util.List.of(IncomePreview.INTEREST_NOT_SPLIT));

        assertThat(line.interestNotSplit()).isTrue();
        assertThat(loan(null).interestFor(Money.of("5000.00"))).isNull();
    }

    @Test
    @DisplayName("com juros informados não há aviso")
    void withInterestNoWarning() {
        assertThat(loan("40.00").interestFor(Money.of("5000.00"))).isEqualTo(Money.of("40.00"));
    }

    @Test
    @DisplayName("juros só existem em LOAN e precisam ser menores que a parcela")
    void invalidInterest() {
        assertThatThrownBy(() -> new PayrollDeduction(DeductionKind.BENEFIT, "VR", Money.of("100.00"), null, null, null, null,
                Money.of("1.00"))).isInstanceOf(IncomeProfileInvalidException.class);
        assertThatThrownBy(() -> loan("300.00")).isInstanceOf(IncomeProfileInvalidException.class);
    }
}
