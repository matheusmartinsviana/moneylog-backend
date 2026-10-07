package com.moneylog.core.debt.domain;

import java.time.LocalDate;
import java.util.Collection;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

/**
 * Saldo com uma pessoa (S-142): {@code net} = Σ saldo devedor das dívidas {@code RECEIVABLE} em aberto − Σ das {@code PAYABLE}
 * em aberto, com sinal (positivo = "me devem"). Só entram as dívidas {@code OPEN}/{@code PARTIALLY_SETTLED}: as
 * {@code SETTLED}, {@code CANCELED} e {@code VOIDED} ficam no histórico e fora do saldo.
 *
 * @param openDebts   dívidas em aberto
 * @param nextDueDate menor previsão entre as dívidas em aberto que têm previsão; {@code null} se nenhuma tem
 */
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

    /** Texto da tela: {@code RECEIVABLE} = "ele deve", {@code PAYABLE} = "você deve", {@code NONE} = quite. */
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
