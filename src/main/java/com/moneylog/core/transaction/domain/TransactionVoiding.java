package com.moneylog.core.transaction.domain;

import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.sk.ledger.VoidDecision;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionVoidRefusedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidContextMissingException;

public final class TransactionVoiding {

    private TransactionVoiding() {
    }

    public static void voidTransaction(Transaction transaction, VoidContext context) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(context, "context");
        if (transaction.debtId() != null) {
            voidDebtTransaction(transaction, context);
        } else if (transaction.cardId() != null) {
            if (context.card() == null || context.chain() == null) {
                throw new VoidContextMissingException(transaction.type(), "o cartão e a cadeia de faturas");
            }
            InvoiceChain chain = context.chain();
            transaction.voidTransaction(context.reason(), context.now(),
                    new CardTransactionVoidPolicy(context.card(), chain, context.today(), context.activeRefunds()));
        } else if (!context.shareDebts().isEmpty()) {
            voidSharedExpense(transaction, context);
        } else {
            requireAccountContext(transaction, context);
            transaction.voidTransaction(context.reason(), context.now(),
                    TransactionVoidPolicy.accountTransactions(context.accounts(), context.activeRefunds()));
        }
    }

    private static void voidSharedExpense(Transaction transaction, VoidContext context) {
        requireAccountContext(transaction, context);
        String validReason = transaction.prepareVoid(context.reason(), context.now(),
                TransactionVoidPolicy.accountTransactions(context.accounts(), context.activeRefunds()));
        for (Debt debt : context.shareDebts()) {
            debt.ensureCanVoidTransaction(transaction.id());
        }
        transaction.applyVoid(validReason, context.now());
        for (Debt debt : context.shareDebts()) {
            debt.markOriginVoided(transaction.id(), validReason, context.today());
        }
    }

    private static void requireAccountContext(Transaction transaction, VoidContext context) {
        for (UUID accountId : new UUID[] {transaction.accountId(), transaction.counterpartAccountId()}) {
            if (accountId != null && context.accounts().stream().noneMatch(account -> accountId.equals(account.id()))) {
                throw new VoidContextMissingException(transaction.type(), "a conta " + accountId + " (withAccounts)");
            }
        }
        if (transaction.type() == TransactionType.EXPENSE && context.activeRefunds() == null) {
            throw new VoidContextMissingException(transaction.type(), "a soma dos reembolsos ativos (withActiveRefunds)");
        }
    }

    private static void voidDebtTransaction(Transaction transaction, VoidContext context) {
        Debt debt = context.debt();
        if (debt == null) {
            throw new VoidContextMissingException(transaction.type(), "a dívida");
        }
        if (context.accounts().stream().anyMatch(account -> !account.isActive())) {
            throw new TransactionVoidRefusedException(VoidDecision.refuse(TransactionVoidPolicy.ACCOUNT_ARCHIVED,
                    "a movimentação toca uma conta arquivada; desarquive a conta para estornar."));
        }
        String validReason = transaction.prepareVoid(context.reason(), context.now(), new DebtTransactionVoidPolicy(debt));
        debt.ensureCanVoidTransaction(transaction.id());
        transaction.applyVoid(validReason, context.now());
        if (transaction.id().equals(debt.originTransactionId())) {
            debt.markOriginVoided(transaction.id(), validReason, context.today());
        } else {
            debt.voidSettlement(transaction.id());
        }
    }
}
