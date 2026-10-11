package com.moneylog.core.transaction.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.InvoiceItem;
import com.moneylog.core.card.domain.InvoiceItemKind;
import com.moneylog.core.card.domain.InvoicePayment;
import com.moneylog.core.card.domain.TransactionInstallment;

public final class CardInvoices {

    private CardInvoices() {
    }

    public static InvoiceChain chainFor(CreditCard card, Collection<Transaction> transactions) {
        List<InvoiceItem> items = new ArrayList<>();
        List<InvoicePayment> payments = new ArrayList<>();
        for (Transaction tx : transactions) {
            if (!card.id().equals(tx.cardId())) {
                continue;
            }
            switch (tx.type()) {
                case CARD_PURCHASE -> {
                    for (TransactionInstallment i : tx.installments()) {
                        items.add(new InvoiceItem(tx.id(), i.id(), InvoiceItemKind.INSTALLMENT, i.amount(), i.effectiveDate(),
                                i.invoiceClosingDate(), i.lateEntry(), tx.isActive() && !i.isCanceled()));
                    }
                }
                case REFUND -> items.add(new InvoiceItem(tx.id(), null, InvoiceItemKind.CREDIT, tx.amount(), tx.date(),
                        tx.invoiceClosingDate(), tx.lateEntry(), tx.isActive()));
                case INVOICE_PAYMENT -> payments.add(new InvoicePayment(tx.id(), tx.invoiceClosingDate(), tx.amount(), tx.date(),
                        tx.isActive()));
                default -> { }
            }
        }
        return new InvoiceChain(card.id(), card.calendar(), items, payments);
    }
}
