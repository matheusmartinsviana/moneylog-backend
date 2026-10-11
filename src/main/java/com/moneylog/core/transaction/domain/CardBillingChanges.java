package com.moneylog.core.transaction.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.moneylog.core.card.domain.BillingChange;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.TransactionInstallment;

public final class CardBillingChanges {

    private CardBillingChanges() {
    }

    public static List<Transaction> changeBillingDays(CreditCard card, Collection<Transaction> transactions, int newClosingDay,
                                                      int newDueDay, LocalDate today) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(transactions, "transactions");
        List<Transaction> ofCard = transactions.stream().filter(t -> card.id().equals(t.cardId())).toList();
        CardInvoices.chainFor(card, ofCard);

        BillingChange change = card.planBillingChange(newClosingDay, newDueDay, today);
        Map<Transaction, List<TransactionInstallment>> installments = new LinkedHashMap<>();
        Map<Transaction, LocalDate> credits = new LinkedHashMap<>();
        for (Transaction tx : ofCard) {
            switch (tx.type()) {
                case CARD_PURCHASE -> {
                    List<TransactionInstallment> reallocated = change.reallocateInstallments(tx.installments());
                    if (movedAny(tx.installments(), reallocated)) {
                        installments.put(tx, reallocated);
                    }
                }
                case REFUND -> {
                    LocalDate target = change.reallocateDated(tx.invoiceClosingDate(), tx.date());
                    if (!target.equals(tx.invoiceClosingDate())) {
                        credits.put(tx, target);
                    }
                }
                case INVOICE_PAYMENT -> change.keepPayment(tx.invoiceClosingDate());
                default -> { }
            }
        }

        card.applyBillingChange(change);
        installments.forEach(Transaction::reallocateInstallments);
        credits.forEach(Transaction::reallocateInvoice);
        CardInvoices.chainFor(card, ofCard);

        List<Transaction> changed = new ArrayList<>(installments.keySet());
        credits.keySet().stream().filter(t -> !changed.contains(t)).forEach(changed::add);
        return List.copyOf(changed);
    }

    private static boolean movedAny(List<TransactionInstallment> before, List<TransactionInstallment> after) {
        Map<Object, LocalDate> old = new LinkedHashMap<>();
        before.forEach(i -> old.put(i.id(), i.invoiceClosingDate()));
        return after.stream().anyMatch(i -> !i.invoiceClosingDate().equals(old.get(i.id())));
    }
}
