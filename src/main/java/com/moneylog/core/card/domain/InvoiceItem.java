package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

/**
 * Item de uma fatura (D1 §2.5.3): parcela de {@code CARD_PURCHASE} ou crédito de {@code REFUND} no cartão.
 *
 * @param amount             sempre positivo; o sinal vem do {@code kind}
 * @param invoiceClosingDate fatura do item (S-13)
 * @param lateEntry          lançamento tardio (S-14)
 * @param active             falso se a movimentação foi estornada ou a parcela cancelada
 */
public record InvoiceItem(UUID transactionId, UUID installmentId, InvoiceItemKind kind, Money amount, LocalDate date,
                          LocalDate invoiceClosingDate, boolean lateEntry, boolean active) {

    public InvoiceItem {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(invoiceClosingDate, "invoiceClosingDate");
    }

    /** Efeito no total da fatura: parcela soma, crédito subtrai. */
    public Money signedAmount() {
        return kind == InvoiceItemKind.INSTALLMENT ? amount : amount.negate();
    }

    public InvoiceItem deactivate() {
        return new InvoiceItem(transactionId, installmentId, kind, amount, date, invoiceClosingDate, lateEntry, false);
    }
}
