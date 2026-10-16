package com.moneylog.core.finance.app.cards;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceItem;
import com.moneylog.core.card.domain.InvoiceItemKind;
import com.moneylog.core.card.domain.InvoicePayment;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PersonAmount;
import com.moneylog.core.transaction.domain.PurchaseShares;
import com.moneylog.core.transaction.domain.Transaction;

public final class InvoiceViews {

    private InvoiceViews() {
    }

    public static Map<UUID, Transaction> index(FinancialRecords records) {
        return records.transactions().stream().collect(Collectors.toMap(Transaction::id, Function.identity()));
    }

    public static InvoiceDetails details(UUID cardId, CreditCardInvoice invoice, Map<UUID, Transaction> transactions, LocalDate today) {
        InvoiceView view = InvoiceView.of(cardId, invoice, today);
        List<PersonAmount> byPerson = shareByPerson(invoice.items(), transactions);
        Money thirdParty = Money.sum(byPerson.stream().map(PersonAmount::amount).toList(), view.total().currency());
        return new InvoiceDetails(view, items(invoice.items(), transactions, false), payments(invoice.payments(), transactions),
                view.total().subtract(thirdParty), thirdParty, byPerson, null, null, 0);
    }

    private static List<PersonAmount> shareByPerson(List<InvoiceItem> items, Map<UUID, Transaction> transactions) {
        Map<UUID, Money> totals = new LinkedHashMap<>();
        items.stream().filter(InvoiceItem::active).filter(item -> item.kind() == InvoiceItemKind.INSTALLMENT).forEach(item -> {
            Transaction transaction = transactions.get(item.transactionId());
            PurchaseShares shares = transaction == null ? null : transaction.purchaseShares();
            if (shares != null) {
                shares.split(item.amount()).byPerson().forEach(part -> totals.merge(part.personId(), part.amount(), Money::add));
            }
        });
        return totals.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new PersonAmount(entry.getKey(), entry.getValue())).toList();
    }

    public static List<InvoiceDetails.Item> items(List<InvoiceItem> items, Map<UUID, Transaction> transactions,
                                                  boolean onlyInstallments) {
        return items.stream().filter(InvoiceItem::active)
                .filter(item -> !onlyInstallments || item.kind() == InvoiceItemKind.INSTALLMENT)
                .map(item -> item(item, transactions.get(item.transactionId())))
                .sorted(Comparator.comparing(InvoiceDetails.Item::date)
                        .thenComparing(item -> item.number() == null ? Integer.MAX_VALUE : item.number())
                        .thenComparing(InvoiceDetails.Item::description)
                        .thenComparing(item -> item.transactionId().toString()))
                .toList();
    }

    private static InvoiceDetails.Item item(InvoiceItem item, Transaction transaction) {
        String description = transaction == null || transaction.description() == null ? "" : transaction.description();
        Integer number = null;
        Integer total = null;
        if (item.kind() == InvoiceItemKind.INSTALLMENT && transaction != null) {
            TransactionInstallment installment = transaction.installments().stream()
                    .filter(i -> i.id().equals(item.installmentId())).findFirst().orElse(null);
            if (installment != null) {
                number = installment.number();
                total = installment.total();
            }
        }
        return new InvoiceDetails.Item(item.kind(), item.transactionId(), description, number, total, item.amount(), item.date(),
                item.lateEntry());
    }

    private static List<InvoiceDetails.Payment> payments(List<InvoicePayment> payments, Map<UUID, Transaction> transactions) {
        return payments.stream().filter(InvoicePayment::active)
                .map(p -> new InvoiceDetails.Payment(p.transactionId(), transactions.get(p.transactionId()).accountId(), p.amount(),
                        p.date()))
                .sorted(Comparator.comparing(InvoiceDetails.Payment::date).thenComparing(p -> p.transactionId().toString()))
                .toList();
    }
}
