package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import com.moneylog.core.card.exception.CardExceptions.InvalidInvoiceOperationException;
import com.moneylog.core.card.exception.CardExceptions.InvoiceChainInconsistentException;
import com.moneylog.core.card.exception.CardExceptions.InvoiceClosingDateNotFoundException;
import com.moneylog.core.sk.money.Money;

public final class InvoiceChain {

    public static final String INVOICE_NOT_PAYABLE = "INVOICE_NOT_PAYABLE";
    public static final String INVOICE_PAID = "INVOICE_PAID";
    public static final String INVOICE_CLOSED_REQUIRES_LATE_ENTRY = "INVOICE_CLOSED_REQUIRES_LATE_ENTRY";

    private final UUID cardId;
    private final InvoiceCalendar calendar;
    private final List<InvoiceItem> items;
    private final List<InvoicePayment> payments;
    private List<CreditCardInvoice> memo;
    private YearMonth memoFirst;

    public InvoiceChain(UUID cardId, InvoiceCalendar calendar, Collection<InvoiceItem> items, Collection<InvoicePayment> payments) {
        this.cardId = Objects.requireNonNull(cardId, "cardId");
        this.calendar = Objects.requireNonNull(calendar, "calendar");
        this.items = new ArrayList<>(items);
        this.payments = new ArrayList<>(payments);
        this.items.forEach(i -> requireInCalendar(i.invoiceClosingDate(), "item da movimentação " + i.transactionId()));
        this.payments.forEach(p -> requireInCalendar(p.invoiceClosingDate(), "pagamento " + p.transactionId()));
    }

    public static InvoiceChain empty(CreditCard card) {
        return new InvoiceChain(card.id(), card.calendar(), List.of(), List.of());
    }

    public UUID cardId() {
        return cardId;
    }

    public void ensureFor(UUID expectedCardId) {
        if (!cardId.equals(expectedCardId)) {
            throw new InvoiceChainInconsistentException("a cadeia é do cartão " + cardId + ", não do cartão " + expectedCardId + ".");
        }
    }

    public List<CreditCardInvoice> invoices() {
        return new ArrayList<>(memoized());
    }

    public CreditCardInvoice invoice(LocalDate closingDate) {
        requireInCalendar(closingDate, "fatura consultada");
        return invoiceAt(closingDate);
    }

    public boolean hasClosingDate(LocalDate closingDate) {
        return calendar.isClosingDate(closingDate);
    }

    public InvoiceStatus statusOf(LocalDate closingDate, LocalDate today) {
        return invoice(closingDate).statusAt(today);
    }

    public CreditCardInvoice current(LocalDate today) {
        return invoice(calendar.periodFor(today).closingDate());
    }

    public Money creditBalance() {
        List<CreditCardInvoice> all = memoized();
        return all.isEmpty() ? CreditCardInvoice.zero() : all.getLast().creditCarry();
    }

    public void ensureCanReceiveItem(LocalDate closingDate, boolean lateEntry, LocalDate today) {
        requireExistingInvoice(closingDate);
        if (isUntouchedIssued(closingDate, today)) {
            if (!lateEntry) {
                throw new InvalidInvoiceOperationException(INVOICE_CLOSED_REQUIRES_LATE_ENTRY,
                        "Fatura fechada só recebe item como lançamento tardio.");
            }
            return;
        }
        switch (statusOf(closingDate, today)) {
            case FUTURE, OPEN -> { }
            case CLOSED, OVERDUE -> {
                if (!lateEntry) {
                    throw new InvalidInvoiceOperationException(INVOICE_CLOSED_REQUIRES_LATE_ENTRY,
                            "Fatura fechada só recebe item como lançamento tardio.");
                }
            }
            case PAID -> throw new InvalidInvoiceOperationException(INVOICE_PAID, "Fatura paga não recebe item novo.");
        }
    }

    public void ensurePayable(LocalDate closingDate, LocalDate today) {
        requireExistingInvoice(closingDate);
        InvoiceStatus status = statusOf(closingDate, today);
        if (status == InvoiceStatus.FUTURE || status == InvoiceStatus.PAID) {
            throw new InvalidInvoiceOperationException(INVOICE_NOT_PAYABLE,
                    "A fatura " + status + " não pode ser paga; pague a fatura corrente e o excedente é transportado.");
        }
    }

    public boolean isUntouchedIssued(LocalDate closingDate, LocalDate today) {
        requireExistingInvoice(closingDate);
        CreditCardInvoice invoice = invoice(closingDate);
        return invoice.statusAt(today) == InvoiceStatus.PAID && invoice.items().isEmpty() && invoice.payments().isEmpty()
                && invoice.carryIn().isZero() && !today.isBefore(invoice.closingDate()) && !today.isAfter(invoice.dueDate());
    }

    public void addItem(InvoiceItem item, LocalDate today) {
        ensureCanReceiveItem(item.invoiceClosingDate(), item.lateEntry(), today);
        items.add(item);
        invalidate();
    }

    public void registerPayment(InvoicePayment payment, LocalDate today) {
        ensurePayable(payment.invoiceClosingDate(), today);
        payments.add(payment);
        invalidate();
    }

    public void deactivateTransaction(UUID transactionId) {
        items.replaceAll(i -> i.transactionId().equals(transactionId) ? i.deactivate() : i);
        payments.replaceAll(p -> p.transactionId().equals(transactionId) ? p.deactivate() : p);
        invalidate();
    }

    public void deactivateInstallment(UUID installmentId) {
        items.replaceAll(i -> installmentId.equals(i.installmentId()) ? i.deactivate() : i);
        invalidate();
    }

    public InvoiceCalendar calendar() {
        return calendar;
    }

    private void requireExistingInvoice(LocalDate closingDate) {
        Objects.requireNonNull(closingDate, "closingDate");
        if (!calendar.isClosingDate(closingDate)) {
            throw new InvoiceClosingDateNotFoundException(closingDate);
        }
    }

    private void requireInCalendar(LocalDate closingDate, String what) {
        if (!calendar.isClosingDate(closingDate)) {
            throw new InvoiceChainInconsistentException(what + " aponta para " + closingDate
                    + ", que não é fechamento do calendário do cartão " + cardId + ".");
        }
    }

    private void invalidate() {
        memo = null;
        memoFirst = null;
    }

    private Stream<LocalDate> movementClosings() {
        return Stream.concat(items.stream().map(InvoiceItem::invoiceClosingDate), payments.stream().map(InvoicePayment::invoiceClosingDate));
    }

    private List<CreditCardInvoice> memoized() {
        if (memo == null) {
            YearMonth first = movementClosings().map(YearMonth::from).min(YearMonth::compareTo).orElse(null);
            YearMonth last = movementClosings().map(YearMonth::from).max(YearMonth::compareTo).orElse(null);
            List<CreditCardInvoice> result = new ArrayList<>();
            if (first != null) {
                Money carry = CreditCardInvoice.zero();
                for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
                    CreditCardInvoice invoice = build(m, carry);
                    result.add(invoice);
                    carry = invoice.creditCarry();
                }
            }
            memo = result;
            memoFirst = first;
        }
        return memo;
    }

    private CreditCardInvoice invoiceAt(LocalDate closingDate) {
        YearMonth target = YearMonth.from(closingDate);
        List<CreditCardInvoice> chain = memoized();
        if (chain.isEmpty() || target.isBefore(memoFirst)) {
            return build(target, CreditCardInvoice.zero());
        }
        int index = (int) memoFirst.until(target, java.time.temporal.ChronoUnit.MONTHS);
        if (index < chain.size()) {
            return chain.get(index);
        }
        Money carry = chain.getLast().creditCarry();
        CreditCardInvoice invoice = null;
        for (YearMonth m = memoFirst.plusMonths(chain.size()); !m.isAfter(target); m = m.plusMonths(1)) {
            invoice = build(m, carry);
            carry = invoice.creditCarry();
        }
        return invoice;
    }

    private CreditCardInvoice build(YearMonth month, Money carryIn) {
        InvoicePeriod period = calendar.periodClosingIn(month);
        return new CreditCardInvoice(period,
                items.stream().filter(i -> i.invoiceClosingDate().equals(period.closingDate())).toList(),
                payments.stream().filter(p -> p.invoiceClosingDate().equals(period.closingDate())).toList(),
                carryIn);
    }
}
