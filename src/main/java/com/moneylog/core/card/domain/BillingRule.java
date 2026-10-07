package com.moneylog.core.card.domain;

import java.time.YearMonth;
import java.util.Objects;

import com.moneylog.core.sk.time.DayOfMonthRule;

/**
 * Regra de fechamento/vencimento que vale a partir de um mês de fechamento (S-42). A primeira regra do cartão vale
 * também para os meses anteriores a ela.
 */
public record BillingRule(YearMonth effectiveFrom, DayOfMonthRule closingDay, DayOfMonthRule dueDay) {

    public BillingRule {
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(closingDay, "closingDay");
        Objects.requireNonNull(dueDay, "dueDay");
    }

    /** D1 §2.5.1 regra 3: vencimento no mesmo mês do fechamento se {@code v > c}; senão, no mês seguinte. */
    public boolean dueInSameMonth() {
        return dueDay.day() > closingDay.day();
    }
}
