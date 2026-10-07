package com.moneylog.core.transaction.domain;

import com.moneylog.core.debt.domain.Debt;

/** Resultado de uma movimentação de dívida: a movimentação e a dívida criada ou atualizada, gravadas juntas. */
public record DebtOperation(Transaction transaction, Debt debt) {
}
