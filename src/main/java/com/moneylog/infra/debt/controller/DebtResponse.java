package com.moneylog.infra.debt.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtKind;
import com.moneylog.core.debt.domain.EffectiveRate;
import com.moneylog.core.debt.domain.DebtOrigin;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.sk.money.Money;

public record DebtResponse(UUID id, PersonRefResponse person, String creditorName, DebtDirection direction, DebtOrigin origin, UUID originTransactionId,
                           Money principal, Money settled, Money outstanding, DebtStatus status, LocalDate date, LocalDate expectedDate,
                           boolean overdue, String description, CategoryRef category, LocalDate closureDate, String closureReason,
                           List<Settlement> settlements, boolean cancelable, boolean voidable, DebtView.VoidBlockedBy voidBlockedBy,
                           long version, Instant createdAt, Instant updatedAt, DebtKind kind, String channel,
                           EffectiveRateResponse effectiveRate) {

    public record EffectiveRateResponse(long monthlyBps, long annualBps, String method) {
    }

    public record CategoryRef(UUID id, String name) {
    }

    public record Settlement(UUID transactionId, Money amount, LocalDate date, boolean active) {
    }

    public static DebtResponse of(DebtView view) {
        Debt d = view.debt();
        return new DebtResponse(d.id(), view.person() == null ? null : new PersonRefResponse(view.person().id(), view.person().name()),
                d.creditorName(), d.direction(), d.origin(),
                d.originTransactionId(), d.principal(), d.settled(), d.outstanding(), d.status(), d.date(), d.expectedDate(),
                view.overdue(), d.description(), d.categoryId() == null ? null : new CategoryRef(d.categoryId(), view.categoryName()),
                d.closureDate(), d.closureReason(),
                view.settlements().stream().map(s -> new Settlement(s.transactionId(), s.amount(), s.date(), s.active())).toList(),
                view.cancelable(), view.voidable(), view.voidBlockedBy(), view.stored().version(), view.createdAt(), view.updatedAt(),
                d.kind(), d.channel(), view.effectiveRate() == null ? null
                        : new EffectiveRateResponse(view.effectiveRate().monthlyBps(), view.effectiveRate().annualBps(), EffectiveRate.METHOD));
    }
}
