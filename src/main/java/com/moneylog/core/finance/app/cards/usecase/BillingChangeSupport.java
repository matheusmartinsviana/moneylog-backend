package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.card.domain.BillingChange;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoicePeriod;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.finance.app.cards.BillingChangePreviewView;
import com.moneylog.core.transaction.domain.CardBillingChanges;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

final class BillingChangeSupport {

    private BillingChangeSupport() {
    }

    static LocalDate effectiveFromClosingDate(CreditCard card, int closingDay, int dueDay, LocalDate today) {
        BillingChange change = card.planBillingChange(closingDay, dueDay, today);
        return change.before().periodClosingIn(change.effectiveFrom()).closingDate();
    }

    static BillingChangePreviewView preview(CreditCard card, List<Transaction> transactionsOfCard, int closingDay, int dueDay,
                                            LocalDate today) {
        BillingChange change = card.planBillingChange(closingDay, dueDay, today);
        YearMonth from = change.effectiveFrom();
        LocalDate effectiveFrom = change.before().periodClosingIn(from).closingDate();
        InvoicePeriod firstNew = change.after().periodClosingIn(from);

        CreditCard cardCopy = CreditCard.restore(card.id(), card.userId(), card.name(), card.institution(), card.limit(),
                card.defaultPaymentAccountId(), card.billingRules(), card.status(), card.version());
        List<Transaction> copies = transactionsOfCard.stream().map(t -> Transaction.restore(t.state(), t.entries())).toList();
        Map<UUID, LocalDate> before = new HashMap<>();
        for (Transaction t : copies) {
            if (t.type() == TransactionType.CARD_PURCHASE) {
                t.installments().forEach(i -> before.put(i.id(), i.invoiceClosingDate()));
            } else if (t.type() == TransactionType.REFUND) {
                before.put(t.id(), t.invoiceClosingDate());
            }
        }
        List<Transaction> changed = CardBillingChanges.changeBillingDays(cardCopy, copies, closingDay, dueDay, today);
        int installments = 0;
        int credits = 0;
        for (Transaction t : changed) {
            if (!t.isActive()) {
                continue;
            }
            if (t.type() == TransactionType.CARD_PURCHASE) {
                for (TransactionInstallment i : t.installments()) {
                    if (!i.isCanceled() && !i.invoiceClosingDate().equals(before.get(i.id()))) {
                        installments++;
                    }
                }
            } else if (t.type() == TransactionType.REFUND && !t.invoiceClosingDate().equals(before.get(t.id()))) {
                credits++;
            }
        }
        return new BillingChangePreviewView(effectiveFrom, firstNew.closingDate(), firstNew.dueDate(), installments, credits);
    }
}
