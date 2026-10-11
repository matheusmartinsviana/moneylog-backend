package com.moneylog.core.debt.domain;

import java.time.LocalDate;
import java.util.Collection;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public record PersonBalance(Money net, int openDebts, LocalDate nextDueDate) {

    public static PersonBalance of(Collection<Debt> debts) {
        Money net = Money.zero(CurrencyCode.BRL);
        int open = 0;
        LocalDate next = null;
        for (Debt debt : debts) {
            if (!isOpen(debt)) {
                continue;
            }
            open++;
            net = debt.direction() == DebtDirection.RECEIVABLE ? net.add(debt.outstanding()) : net.subtract(debt.outstanding());
            if (debt.expectedDate() != null && (next == null || debt.expectedDate().isBefore(next))) {
                next = debt.expectedDate();
            }
        }
        return new PersonBalance(net, open, next);
    }

    public Direction direction() {
        return net.isPositive() ? Direction.RECEIVABLE : net.isNegative() ? Direction.PAYABLE : Direction.NONE;
    }

    private static boolean isOpen(Debt debt) {
        return debt.status() == DebtStatus.OPEN || debt.status() == DebtStatus.PARTIALLY_SETTLED;
    }

    public enum Direction {
        RECEIVABLE,
        PAYABLE,
        NONE
    }
}
