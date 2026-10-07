package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * Datas de uma fatura (D1 §2.5.1). A identidade é {@code closingDate} (S-13); {@code referenceMonth} (mês do
 * vencimento) é só rótulo. O período vai de {@code periodStart} (fechamento anterior, inclusive) até a véspera do
 * fechamento.
 */
public record InvoicePeriod(LocalDate periodStart, LocalDate closingDate, LocalDate dueDate, YearMonth referenceMonth) {

    public InvoicePeriod {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(closingDate, "closingDate");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(referenceMonth, "referenceMonth");
    }

    public LocalDate periodEnd() {
        return closingDate.minusDays(1);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(periodStart) && date.isBefore(closingDate);
    }

    public YearMonth closingMonth() {
        return YearMonth.from(closingDate);
    }
}
