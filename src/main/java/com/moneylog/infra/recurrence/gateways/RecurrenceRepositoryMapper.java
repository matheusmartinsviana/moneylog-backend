package com.moneylog.infra.recurrence.gateways;

import java.time.LocalDate;
import java.util.Collection;

import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.infra.recurrence.persistence.RecurrenceEntity;

/**
 * {@code RecurringTransaction} ⇄ {@code RecurrenceEntity}. A reidratação usa {@link RecurringTransaction#restore}, que recusa
 * estado gravado corrompido ({@code RECURRENCE_STATE_INCONSISTENT}); as realizadas vêm das movimentações ativas e as puladas, de
 * {@code recurrence_skips}.
 */
public final class RecurrenceRepositoryMapper {

    private RecurrenceRepositoryMapper() {
    }

    public static RecurringTransaction toDomain(RecurrenceEntity e, Collection<LocalDate> realized, Collection<LocalDate> skipped) {
        Schedule schedule = new Schedule(e.getFrequency(), e.getIntervalCount(), e.getIntervalUnit(), e.getStartDate(), e.getEndDate(),
                e.getMaxOccurrences());
        return RecurringTransaction.restore(e.getId(), e.getUserId(), e.getType(),
                Money.ofCents(e.getAmountCents(), CurrencyCode.of(e.getCurrency())), e.getDescription(), e.getCategoryId(),
                e.getAccountId(), e.getCardId(), e.getPaymentMethod(), schedule, realized, skipped, e.isActive());
    }

    public static Versioned<RecurringTransaction> toVersioned(RecurrenceEntity e, Collection<LocalDate> realized,
                                                              Collection<LocalDate> skipped) {
        return new Versioned<>(toDomain(e, realized, skipped), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    public static RecurrenceEntity toEntity(RecurringTransaction r, java.time.Instant now) {
        Schedule s = r.schedule();
        return new RecurrenceEntity(r.id(), r.userId(), r.type(), r.amount().cents(), r.amount().currency().value(), r.description(),
                r.categoryId(), r.accountId(), r.cardId(), r.paymentMethod(), s.frequency(), s.interval(), s.unit(), s.startDate(),
                s.endDate(), s.maxOccurrences(), r.isActive(), now);
    }
}
