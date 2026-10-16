package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.InvoiceItem;
import com.moneylog.core.card.domain.InvoiceItemKind;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.recurrence.domain.Occurrence;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.Transaction;

public final class CommitmentSchedule {

    private final LocalDate today;
    private final List<Commitment> items;
    private final List<Commitment> undated;

    private CommitmentSchedule(LocalDate today, List<Commitment> items, List<Commitment> undated) {
        this.today = today;
        this.items = items.stream().sorted(Comparator.comparing(Commitment::dueDate)).toList();
        this.undated = List.copyOf(undated);
    }

    public static CommitmentSchedule of(FinancialRecords records, Collection<RecurringTransaction> recurrences, LocalDate today) {
        List<Commitment> dated = new ArrayList<>();
        List<Commitment> undated = new ArrayList<>();
        Map<UUID, Transaction> transactions = records.transactionsById();
        for (CreditCard card : records.cards()) {
            InvoiceChain chain = CardInvoices.chainFor(card, records.transactions());
            for (CreditCardInvoice invoice : chain.invoices()) {
                InvoiceStatus status = invoice.statusAt(today);
                if (status == InvoiceStatus.PAID || !invoice.outstanding().isPositive()) {
                    continue;
                }
                boolean closed = status == InvoiceStatus.CLOSED || status == InvoiceStatus.OVERDUE;
                Bucket bucket = status == InvoiceStatus.OVERDUE ? Bucket.OVERDUE : Bucket.of(invoice.dueDate(), today);
                dated.add(new Commitment(closed ? CommitmentKind.INVOICE : CommitmentKind.INVOICE_FORECAST, invoice.dueDate(),
                        invoice.outstanding(), FlowDirection.OUT, bucket, card.name(), card.id(), invoice.closingDate(),
                        installmentsOf(invoice, transactions, bucket), false));
            }
        }
        for (Debt debt : records.debts()) {
            if (debt.status() != DebtStatus.OPEN && debt.status() != DebtStatus.PARTIALLY_SETTLED || debt.deductedInPayroll()) {
                continue;
            }
            boolean payable = debt.direction() == DebtDirection.PAYABLE;
            LocalDate due = debt.expectedDate();
            Commitment c = new Commitment(payable ? CommitmentKind.DEBT_PAYABLE : CommitmentKind.DEBT_RECEIVABLE, due, debt.outstanding(),
                    payable ? FlowDirection.OUT : FlowDirection.IN, due == null ? null : Bucket.of(due, today), debt.description(),
                    debt.id(), null, List.of(), false);
            (due == null ? undated : dated).add(c);
        }
        scheduledTransactions(records, today, dated);
        for (RecurringTransaction recurrence : recurrences) {
            for (Occurrence o : recurrence.occurrences(recurrence.schedule().startDate(), Horizon.M12.endFrom(today))) {
                dated.add(new Commitment(CommitmentKind.RECURRENCE, o.date(), o.amount(), o.direction(), Bucket.of(o.date(), today),
                        o.description(), o.recurrenceId(), null, List.of(), false));
            }
        }
        return new CommitmentSchedule(today, dated, undated);
    }

    private static void scheduledTransactions(FinancialRecords records, LocalDate today, List<Commitment> dated) {
        Set<UUID> cashAccounts = new HashSet<>(records.cashAccountIds());
        for (Transaction tx : records.transactions()) {
            Map<LocalDate, Money> byDate = new TreeMap<>();
            for (LedgerEntry e : tx.entries()) {
                Posting p = e.posting();
                if (!e.active() || p.bookType() != BookType.ACCOUNT || !cashAccounts.contains(p.book().id())
                        || !p.effectiveDate().isAfter(today)) {
                    continue;
                }
                byDate.merge(p.effectiveDate(), p.amount(), Money::add);
            }
            byDate.forEach((date, net) -> {
                if (!net.isZero()) {
                    dated.add(new Commitment(CommitmentKind.SCHEDULED_TRANSACTION, date, net.abs(),
                            net.isPositive() ? FlowDirection.IN : FlowDirection.OUT, Bucket.of(date, today), tx.description() == null ? "" : tx.description(), tx.id(),
                            null, List.of(), false));
                }
            });
        }
    }

    private static List<Commitment> installmentsOf(CreditCardInvoice invoice, Map<UUID, Transaction> transactions, Bucket bucket) {
        List<Commitment> nested = new ArrayList<>();
        for (InvoiceItem item : invoice.items()) {
            if (!item.active() || item.kind() != InvoiceItemKind.INSTALLMENT) {
                continue;
            }
            Transaction tx = transactions.get(item.transactionId());
            TransactionInstallment installment = tx.installments().stream()
                    .filter(i -> i.id().equals(item.installmentId())).findFirst().orElseThrow();
            String label = (tx.description() == null ? "Parcela" : tx.description()) + " " + installment.number() + "/" + installment.total();
            nested.add(new Commitment(CommitmentKind.INSTALLMENT, invoice.dueDate(), item.amount(), FlowDirection.OUT, bucket, label,
                    tx.id(), null, List.of(), true, installment.number(), installment.total()));
        }
        return nested;
    }

    public List<Commitment> items() {
        return items;
    }

    public List<Commitment> undated() {
        return undated;
    }

    public List<Commitment> between(LocalDate from, LocalDate to) {
        return items.stream().filter(c -> !c.dueDate().isBefore(from) && !c.dueDate().isAfter(to)).toList();
    }

    public Money committed(Horizon horizon) {
        return sum(FlowDirection.OUT, horizon.endFrom(today));
    }

    public Money incoming(Horizon horizon) {
        return sum(FlowDirection.IN, horizon.endFrom(today));
    }

    public Money totalOut() {
        return sum(FlowDirection.OUT, LocalDate.MAX);
    }

    public Money totalIn() {
        return sum(FlowDirection.IN, LocalDate.MAX);
    }

    private Money sum(FlowDirection direction, LocalDate until) {
        return Money.sum(items.stream().filter(c -> c.direction() == direction && !c.dueDate().isAfter(until))
                .map(Commitment::amount).toList(), CurrencyCode.BRL);
    }
}
