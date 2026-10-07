package com.moneylog.infra.debt.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtOrigin;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.sk.money.Money;

/** {@code Debt} do contrato (D2 §4.6). */
public record DebtResponse(UUID id, PersonRefResponse person, DebtDirection direction, DebtOrigin origin, UUID originTransactionId,
                           Money principal, Money settled, Money outstanding, DebtStatus status, LocalDate date, LocalDate expectedDate,
                           boolean overdue, String description, CategoryRef category, LocalDate closureDate, String closureReason,
                           List<Settlement> settlements, boolean cancelable, boolean voidable, DebtView.VoidBlockedBy voidBlockedBy,
                           long version, Instant createdAt, Instant updatedAt) {

    public record CategoryRef(UUID id, String name) {
    }

    public record Settlement(UUID transactionId, Money amount, LocalDate date, boolean active) {
    }

    public static DebtResponse of(DebtView view) {
        Debt d = view.debt();
        return new DebtResponse(d.id(), new PersonRefResponse(view.person().id(), view.person().name()), d.direction(), d.origin(),
                d.originTransactionId(), d.principal(), d.settled(), d.outstanding(), d.status(), d.date(), d.expectedDate(),
                view.overdue(), d.description(), d.categoryId() == null ? null : new CategoryRef(d.categoryId(), view.categoryName()),
                d.closureDate(), d.closureReason(),
                view.settlements().stream().map(s -> new Settlement(s.transactionId(), s.amount(), s.date(), s.active())).toList(),
                view.cancelable(), view.voidable(), view.voidBlockedBy(), view.stored().version(), view.createdAt(), view.updatedAt());
    }
}
