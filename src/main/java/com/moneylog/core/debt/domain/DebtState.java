package com.moneylog.core.debt.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record DebtState(UUID id, UUID userId, UUID personId, DebtDirection direction, DebtOrigin origin, Money principal,
                        String description, LocalDate date, LocalDate expectedDate, UUID originTransactionId,
                        DebtClosure closure, LocalDate closureDate, String closureReason, UUID categoryId,
                        String creditorName) {

    public DebtState(UUID id, UUID userId, UUID personId, DebtDirection direction, DebtOrigin origin, Money principal,
                     String description, LocalDate date, LocalDate expectedDate, UUID originTransactionId,
                     DebtClosure closure, LocalDate closureDate, String closureReason, UUID categoryId) {
        this(id, userId, personId, direction, origin, principal, description, date, expectedDate, originTransactionId, closure,
                closureDate, closureReason, categoryId, null);
    }

    public DebtState(UUID id, UUID userId, UUID personId, DebtDirection direction, DebtOrigin origin, Money principal,
                     String description, LocalDate date, LocalDate expectedDate, UUID originTransactionId,
                     DebtClosure closure, LocalDate closureDate, String closureReason) {
        this(id, userId, personId, direction, origin, principal, description, date, expectedDate, originTransactionId, closure,
                closureDate, closureReason, null);
    }
}
