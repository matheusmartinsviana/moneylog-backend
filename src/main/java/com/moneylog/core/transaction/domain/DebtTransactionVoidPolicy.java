package com.moneylog.core.transaction.domain;

import java.util.Objects;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtVoidRules;
import com.moneylog.core.sk.ledger.VoidDecision;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidPolicyNotApplicableException;

public final class DebtTransactionVoidPolicy implements TransactionVoidPolicy {

    private final Debt debt;

    public DebtTransactionVoidPolicy(Debt debt) {
        this.debt = Objects.requireNonNull(debt, "debt");
    }

    @Override
    public VoidDecision evaluate(Transaction transaction) {
        if (!debt.id().equals(transaction.debtId())) {
            throw new VoidPolicyNotApplicableException(transaction.type());
        }
        return transaction.id().equals(debt.originTransactionId())
                ? DebtVoidRules.forOrigin(debt)
                : DebtVoidRules.forSettlement(debt);
    }
}
