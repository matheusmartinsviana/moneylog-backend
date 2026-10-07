package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

/**
 * Fatura (D1 §2.5.3), como retrato calculado: datas gravadas + itens e pagamentos + o {@code carryIn} vindo da cadeia
 * do cartão ({@link InvoiceChain}). Nenhum valor nem status é gravado (S-37).
 *
 * <ul>
 *   <li>{@code total} = Σ parcelas ativas − Σ créditos ativos (pode ser negativo, S-18);</li>
 *   <li>{@code outstanding} = max(0, total − carryIn − paid);</li>
 *   <li>{@code creditCarry} = max(0, carryIn + paid − total), transportado para a fatura seguinte (S-16).</li>
 * </ul>
 * O saldo devedor de um pagamento parcial fica na própria fatura e não é transportado (S-17).
 */
public final class CreditCardInvoice {

    private final InvoicePeriod period;
    private final List<InvoiceItem> items;
    private final List<InvoicePayment> payments;
    private final Money carryIn;

    CreditCardInvoice(InvoicePeriod period, List<InvoiceItem> items, List<InvoicePayment> payments, Money carryIn) {
        this.period = Objects.requireNonNull(period, "period");
        this.items = List.copyOf(items);
        this.payments = List.copyOf(payments);
        this.carryIn = Objects.requireNonNull(carryIn, "carryIn");
    }

    /**
     * Status derivado (S-37): {@code FUTURE} antes do período; {@code OPEN} até a véspera do fechamento; depois,
     * {@code PAID} se {@code outstanding = 0}, senão {@code CLOSED} até o vencimento (inclusive) e {@code OVERDUE}
     * depois dele.
     */
    public InvoiceStatus statusAt(LocalDate today) {
        Objects.requireNonNull(today, "today");
        if (today.isBefore(period.periodStart())) {
            return InvoiceStatus.FUTURE;
        }
        if (today.isBefore(period.closingDate())) {
            return InvoiceStatus.OPEN;
        }
        if (outstanding().isZero()) {
            return InvoiceStatus.PAID;
        }
        return today.isAfter(period.dueDate()) ? InvoiceStatus.OVERDUE : InvoiceStatus.CLOSED;
    }

    public Money total() {
        return Money.sum(items.stream().filter(InvoiceItem::active).map(InvoiceItem::signedAmount).toList(), carryIn.currency());
    }

    public Money paid() {
        return Money.sum(payments.stream().filter(InvoicePayment::active).map(InvoicePayment::amount).toList(), carryIn.currency());
    }

    public Money carryIn() {
        return carryIn;
    }

    public Money outstanding() {
        return max0(total().subtract(carryIn).subtract(paid()));
    }

    public Money creditCarry() {
        return max0(carryIn.add(paid()).subtract(total()));
    }

    public boolean hasLateEntries() {
        return items.stream().anyMatch(i -> i.active() && i.lateEntry());
    }

    public InvoicePeriod period() {
        return period;
    }

    public LocalDate closingDate() {
        return period.closingDate();
    }

    public LocalDate dueDate() {
        return period.dueDate();
    }

    public YearMonth referenceMonth() {
        return period.referenceMonth();
    }

    public List<InvoiceItem> items() {
        return items;
    }

    public List<InvoicePayment> payments() {
        return payments;
    }

    private static Money max0(Money value) {
        return value.isNegative() ? Money.zero(value.currency()) : value;
    }

    static Money zero() {
        return Money.zero(CurrencyCode.BRL);
    }

    @Override
    public String toString() {
        return "Invoice[" + period.closingDate() + ", total " + total() + ", paid " + paid() + ", carryIn " + carryIn
                + ", outstanding " + outstanding() + ", creditCarry " + creditCarry() + "]";
    }
}
