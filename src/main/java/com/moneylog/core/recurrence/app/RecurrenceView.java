package com.moneylog.core.recurrence.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.recurrence.domain.MonthlyEstimate;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;

public record RecurrenceView(Versioned<RecurringTransaction> stored, Category category, Account account, CreditCard card,
                             LocalDate nextOccurrence, Money monthlyEstimate, boolean ended) {

    public RecurringTransaction recurrence() {
        return stored.value();
    }

    public Instant createdAt() {
        return stored.createdAt();
    }

    public Instant updatedAt() {
        return stored.updatedAt();
    }

    public static Money monthlyEstimateOf(RecurringTransaction recurrence, LocalDate today) {
        MonthlyEstimate estimate = MonthlyEstimate.of(List.of(recurrence), today);
        return recurrence.direction() == FlowDirection.IN ? estimate.recurringIncome() : estimate.fixedCosts();
    }

    public static LocalDate nextOccurrenceOf(RecurringTransaction recurrence, LocalDate today) {
        LocalDate horizon = (recurrence.schedule().startDate().isAfter(today) ? recurrence.schedule().startDate() : today).plusMonths(12);
        return recurrence.occurrences(recurrence.schedule().startDate(), horizon).stream().findFirst()
                .map(com.moneylog.core.recurrence.domain.Occurrence::date).orElse(null);
    }
}
