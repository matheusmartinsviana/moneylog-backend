package com.moneylog.core.finance.app.transactions;

import java.time.LocalDate;
import java.util.Collection;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CardVoidRules;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtVoidRules;
import com.moneylog.core.sk.ledger.VoidDecision;
import com.moneylog.core.finance.app.transactions.TransactionDetails.Flags;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.domain.TransactionVoidPolicy;

public final class TransactionFlags {

    private TransactionFlags() {
    }

    public static Flags of(Transaction transaction, Collection<Account> touchedAccounts, boolean hasActiveRefunds) {
        if (!transaction.isActive()) {
            return new Flags(false, false, null);
        }
        if (transaction.cardId() != null) {
            return transaction.type() == TransactionType.INVOICE_PAYMENT ? invoicePayment(touchedAccounts) : new Flags(false, false, null);
        }
        boolean accountTypes = TransactionVoidPolicy.ACCOUNT_ONLY_TYPES.contains(transaction.type());
        boolean archived = touchedAccounts.stream().anyMatch(account -> !account.isActive());
        if (archived) {
            return new Flags(false, false, VoidBlockedBy.ACCOUNT_ARCHIVED);
        }
        if (hasActiveRefunds) {
            return new Flags(accountTypes, false, VoidBlockedBy.HAS_ACTIVE_REFUNDS);
        }
        return new Flags(accountTypes, true, null);
    }

    public static Flags ofDebt(Transaction transaction, Collection<Account> touchedAccounts, Debt debt) {
        if (!transaction.isActive()) {
            return new Flags(false, false, null);
        }
        if (touchedAccounts.stream().anyMatch(account -> !account.isActive())) {
            return new Flags(false, false, VoidBlockedBy.ACCOUNT_ARCHIVED);
        }
        VoidDecision decision = transaction.id().equals(debt.originTransactionId()) ? DebtVoidRules.forOrigin(debt)
                : DebtVoidRules.forSettlement(debt);
        return decision.allowed() ? new Flags(false, true, null) : new Flags(false, false, VoidBlockedBy.valueOf(decision.code()));
    }

    public static Flags ofShares(Transaction transaction, Collection<Account> touchedAccounts, boolean hasActiveRefunds,
                                 Collection<Debt> debts) {
        Flags base = of(transaction, touchedAccounts, hasActiveRefunds);
        if (!base.voidable()) {
            return base;
        }
        for (Debt debt : debts) {
            VoidDecision decision = DebtVoidRules.forOrigin(debt);
            if (!decision.allowed()) {
                return new Flags(base.editable(), false, VoidBlockedBy.valueOf(decision.code()));
            }
        }
        return base;
    }

    public static Flags ofCard(Transaction transaction, Collection<Account> touchedAccounts, boolean hasActiveRefunds,
                               InvoiceChain chain, LocalDate today) {
        if (!transaction.isActive()) {
            return new Flags(false, false, null);
        }
        return switch (transaction.type()) {
            case INVOICE_PAYMENT -> invoicePayment(touchedAccounts);
            case CARD_PURCHASE -> {
                if (!CardVoidRules.forPurchase(transaction.installments(), chain, today).allowed()) {
                    yield new Flags(true, false, VoidBlockedBy.TRANSACTION_IN_CLOSED_INVOICE);
                }
                yield hasActiveRefunds ? new Flags(true, false, VoidBlockedBy.HAS_ACTIVE_REFUNDS) : new Flags(true, true, null);
            }
            case REFUND -> CardVoidRules.forCardRefund(transaction.invoiceClosingDate(), chain, today).allowed()
                    ? new Flags(false, true, null) : new Flags(false, false, VoidBlockedBy.TRANSACTION_IN_CLOSED_INVOICE);
            default -> new Flags(false, false, null);
        };
    }

    private static Flags invoicePayment(Collection<Account> touchedAccounts) {
        boolean archivedAccount = touchedAccounts.stream().anyMatch(account -> !account.isActive());
        return archivedAccount ? new Flags(false, false, VoidBlockedBy.ACCOUNT_ARCHIVED) : new Flags(false, true, null);
    }
}
