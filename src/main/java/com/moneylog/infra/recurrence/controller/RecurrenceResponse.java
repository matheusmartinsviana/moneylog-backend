package com.moneylog.infra.recurrence.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.IntervalUnit;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.infra.recurrence.controller.RecurrenceRequests.RecurrenceType;

public record RecurrenceResponse(UUID id, RecurrenceType type, Money amount, String description, Ref category, Ref account, Ref card,
                                 PaymentMethod method, Frequency frequency, int interval, IntervalUnit unit, LocalDate startDate,
                                 LocalDate endDate, Integer maxOccurrences, boolean active, LocalDate nextOccurrence,
                                 Money monthlyEstimate, boolean ended, int incomeCompetenceOffsetMonths, long version, Instant createdAt,
                                 Instant updatedAt) {

    public record Ref(UUID id, String name) {
    }

    public static RecurrenceResponse of(RecurrenceView view) {
        RecurringTransaction r = view.recurrence();
        Schedule s = r.schedule();
        Account account = view.account();
        CreditCard card = view.card();
        return new RecurrenceResponse(r.id(), RecurrenceType.valueOf(r.type().name()), r.amount(), r.description(),
                new Ref(view.category().id(), view.category().name()), account == null ? null : new Ref(account.id(), account.name()),
                card == null ? null : new Ref(card.id(), card.name()), r.paymentMethod(), s.frequency(), s.interval(), s.unit(),
                s.startDate(), s.endDate(), s.maxOccurrences(), r.isActive(), view.nextOccurrence(), view.monthlyEstimate(), view.ended(),
                r.incomeCompetenceOffsetMonths(), view.stored().version(), view.createdAt(), view.updatedAt());
    }
}
