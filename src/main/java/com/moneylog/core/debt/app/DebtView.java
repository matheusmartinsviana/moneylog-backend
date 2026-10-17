package com.moneylog.core.debt.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtOrigin;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.debt.domain.DebtVoidRules;
import com.moneylog.core.debt.domain.EffectiveRate;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.sk.persistence.Versioned;

public record DebtView(Versioned<Debt> stored, Person person, String categoryName, List<SettlementView> settlements, boolean overdue,
                       boolean cancelable, boolean voidable, VoidBlockedBy voidBlockedBy, EffectiveRate effectiveRate) {

    public enum VoidBlockedBy {
        DEBT_HAS_SETTLEMENTS,
        DEBT_CLOSED,
        HAS_ORIGIN_TRANSACTION
    }

    public record SettlementView(UUID transactionId, com.moneylog.core.sk.money.Money amount, LocalDate date, boolean active) {
    }

    public Debt debt() {
        return stored.value();
    }

    public Instant createdAt() {
        return stored.createdAt();
    }

    public Instant updatedAt() {
        return stored.updatedAt();
    }

    public static DebtView of(Versioned<Debt> stored, Person person, String categoryName, Map<UUID, LocalDate> settlementDates,
                              LocalDate today) {
        return of(stored, person, categoryName, settlementDates, today, null);
    }

    public static DebtView of(Versioned<Debt> stored, Person person, String categoryName, Map<UUID, LocalDate> settlementDates,
                              LocalDate today, EffectiveRate effectiveRate) {
        Debt debt = stored.value();
        List<SettlementView> settlements = debt.settlements().stream()
                .map(s -> new SettlementView(s.transactionId(), s.amount(), settlementDates.get(s.transactionId()), s.active()))
                .sorted(java.util.Comparator.comparing(SettlementView::date, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        boolean cancelable = !debt.carriedByCard()
                && (debt.status() == DebtStatus.OPEN || debt.status() == DebtStatus.PARTIALLY_SETTLED);
        VoidBlockedBy blocked = null;
        if (debt.origin() == DebtOrigin.WITH_MOVEMENT) {
            blocked = VoidBlockedBy.HAS_ORIGIN_TRANSACTION;
        } else {
            var decision = DebtVoidRules.forOrigin(debt);
            if (!decision.allowed()) {
                blocked = VoidBlockedBy.valueOf(decision.code());
            }
        }
        return new DebtView(stored, person, categoryName, settlements, debt.isOverdue(today), cancelable, blocked == null, blocked,
                effectiveRate);
    }
}
