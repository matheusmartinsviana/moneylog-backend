package com.moneylog.core.transaction.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.sk.ledger.VoidDecision;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidPolicyNotApplicableException;

@FunctionalInterface
public interface TransactionVoidPolicy {

    VoidDecision evaluate(Transaction transaction);

    Set<TransactionType> ACCOUNT_ONLY_TYPES = EnumSet.of(TransactionType.EXPENSE, TransactionType.INCOME,
            TransactionType.TRANSFER, TransactionType.INVESTMENT, TransactionType.ADJUSTMENT, TransactionType.REFUND);

    String ACCOUNT_ARCHIVED = "ACCOUNT_ARCHIVED";

    String HAS_ACTIVE_REFUNDS = "TRANSACTION_HAS_ACTIVE_REFUNDS";

    static TransactionVoidPolicy accountTransactions(Collection<Account> touchedAccounts, Money activeRefunds) {
        TransactionVoidPolicy base = accountTransactions();
        return transaction -> {
            VoidDecision decision = base.evaluate(transaction);
            if (!decision.allowed()) {
                return decision;
            }
            if (touchedAccounts.stream().anyMatch(account -> !account.isActive())) {
                return VoidDecision.refuse(ACCOUNT_ARCHIVED,
                        "a movimentação toca uma conta arquivada; desarquive a conta para estornar.");
            }
            if (activeRefunds != null && activeRefunds.isPositive()) {
                return VoidDecision.refuse(HAS_ACTIVE_REFUNDS, "a despesa tem reembolsos ativos; estorne os reembolsos antes.");
            }
            return VoidDecision.allow();
        };
    }

    static TransactionVoidPolicy accountTransactions() {
        return transaction -> {
            boolean accountOnly = ACCOUNT_ONLY_TYPES.contains(transaction.type()) && transaction.cardId() == null;
            if (!accountOnly) {
                throw new VoidPolicyNotApplicableException(transaction.type());
            }
            return VoidDecision.allow();
        };
    }
}
