package com.moneylog.core.debt.domain;

import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

/** Quitação de uma dívida: a movimentação real ({@code MONEY_RECEIVED}/{@code DEBT_PAYMENT}), o valor e se está ativa. */
public record DebtSettlement(UUID transactionId, Money amount, boolean active) {

    public DebtSettlement {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(amount, "amount");
    }

    public DebtSettlement deactivate() {
        return new DebtSettlement(transactionId, amount, false);
    }
}
