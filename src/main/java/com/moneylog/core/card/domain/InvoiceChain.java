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

/**
 * Cadeia de faturas de um cartão (D1 §2.5.3): calcula {@code carryIn}/{@code creditCarry} em ordem de fechamento e o
 * status de cada fatura. É reconstruída a partir dos itens e pagamentos ativos; o transporte de crédito é só
 * apresentação (no razão há um posting por pagamento e um por crédito).
 *
 * <p>Invariante (D1 §4, item 11): Σ outstanding − creditCarry da última = Σ total − Σ paid = −(saldo do livro CARD).
 *
 * <p>A cadeia é de <b>um</b> cartão ({@link #cardId()}): fábricas e guardas recusam cadeia de outro cartão (A3). Todo
 * item e pagamento aponta para uma data que é fechamento do calendário; o contrário lança
 * {@link InvoiceChainInconsistentException} em vez de sumir com o item (C1, depois de uma mudança de dia sem
 * realocação).
 */
public final class InvoiceChain {

    public static final String INVOICE_NOT_PAYABLE = "INVOICE_NOT_PAYABLE";
    public static final String INVOICE_PAID = "INVOICE_PAID";
    public static final String INVOICE_CLOSED_REQUIRES_LATE_ENTRY = "INVOICE_CLOSED_REQUIRES_LATE_ENTRY";

    private final UUID cardId;
    private final InvoiceCalendar calendar;
    private final List<InvoiceItem> items;
    private final List<InvoicePayment> payments;
    /** B5: a cadeia da primeira à última fatura com movimento, calculada uma vez por estado (invalidada pelas mutações). */
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

    /** Cadeia sem movimentos do cartão. */
    public static InvoiceChain empty(CreditCard card) {
        return new InvoiceChain(card.id(), card.calendar(), List.of(), List.of());
    }

    public UUID cardId() {
        return cardId;
    }

    /**
     * Garante que a cadeia é do cartão informado (A3). Cadeia de outro cartão é erro de ligação do caso de uso.
     *
     * @throws InvoiceChainInconsistentException se for de outro cartão
     */
    public void ensureFor(UUID expectedCardId) {
        if (!cardId.equals(expectedCardId)) {
            throw new InvoiceChainInconsistentException("a cadeia é do cartão " + cardId + ", não do cartão " + expectedCardId + ".");
        }
    }

    /** Faturas com algum item ou pagamento (ativo ou não), contíguas da primeira à última, em ordem de fechamento. */
    public List<CreditCardInvoice> invoices() {
        return new ArrayList<>(memoized());
    }

    /**
     * Fatura que fecha em {@code closingDate} (pode estar vazia), com o crédito transportado das anteriores.
     *
     * @throws InvoiceChainInconsistentException se {@code closingDate} não é fechamento do calendário
     */
    public CreditCardInvoice invoice(LocalDate closingDate) {
        requireInCalendar(closingDate, "fatura consultada");
        return invoiceAt(closingDate);
    }

    /** {@code true} se existe fatura (vazia ou não) que fecha exatamente em {@code closingDate}. */
    public boolean hasClosingDate(LocalDate closingDate) {
        return calendar.isClosingDate(closingDate);
    }

    public InvoiceStatus statusOf(LocalDate closingDate, LocalDate today) {
        return invoice(closingDate).statusAt(today);
    }

    /** Fatura corrente: a única {@code OPEN} em {@code today}. */
    public CreditCardInvoice current(LocalDate today) {
        return invoice(calendar.periodFor(today).closingDate());
    }

    /** Crédito no cartão: o {@code creditCarry} da última fatura com movimento. */
    public Money creditBalance() {
        List<CreditCardInvoice> all = memoized();
        return all.isEmpty() ? CreditCardInvoice.zero() : all.getLast().creditCarry();
    }

    /**
     * Valida a entrada de um item (matriz status × operação, T6a CA9): {@code FUTURE}/{@code OPEN} aceitam;
     * {@code CLOSED}/{@code OVERDUE} só como lançamento tardio; {@code PAID} nunca recebe item (S-14).
     */
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

    /**
     * Pagamento em {@code OPEN} (antecipação), {@code CLOSED} e {@code OVERDUE}; recusado em {@code FUTURE}/{@code PAID}.
     * A data precisa ser exatamente um fechamento do calendário (A3): "24/10" não é a fatura de 25/10.
     */
    public void ensurePayable(LocalDate closingDate, LocalDate today) {
        requireExistingInvoice(closingDate);
        InvoiceStatus status = statusOf(closingDate, today);
        if (status == InvoiceStatus.FUTURE || status == InvoiceStatus.PAID) {
            throw new InvalidInvoiceOperationException(INVOICE_NOT_PAYABLE,
                    "A fatura " + status + " não pode ser paga; pague a fatura corrente e o excedente é transportado.");
        }
    }

    /**
     * Fatura já fechada, ainda dentro do prazo de vencimento e sem nenhum item ou pagamento (nem desativado): o status derivado a
     * chama de {@code PAID} (total zero), mas ninguém a pagou; o banco a emitiu e o app só não recebeu os itens ainda (carga de uma
     * fatura emitida, lançamento tardio). Depois do vencimento volta a valer a regra geral (fatura sem movimento = paga, S-14).
     */
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

    /** Desativa itens e pagamentos de uma movimentação estornada. */
    public void deactivateTransaction(UUID transactionId) {
        items.replaceAll(i -> i.transactionId().equals(transactionId) ? i.deactivate() : i);
        payments.replaceAll(p -> p.transactionId().equals(transactionId) ? p.deactivate() : p);
        invalidate();
    }

    /** Desativa o item de uma parcela cancelada. */
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

    /** A cadeia contígua do mês da primeira ao da última movimentação (vazia sem movimento); calculada uma vez por estado. */
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

    /**
     * A fatura que fecha no mês de {@code closingDate}: sai da cadeia memoizada quando está dentro dela; depois da última
     * movimentação só há o crédito da última fatura para transportar; antes da primeira não há nada a transportar.
     */
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
