package com.moneylog.core.card.domain;

/**
 * Status da fatura, <b>derivado na leitura</b> (S-37) a partir de hoje, das datas e do {@code outstanding}. Não é
 * gravado e não existe job (D1 §2.5.3).
 */
public enum InvoiceStatus {
    /** {@code hoje < periodStart}. */
    FUTURE,
    /** {@code periodStart ≤ hoje < closingDate}: a fatura corrente (uma por cartão). */
    OPEN,
    /** {@code closingDate ≤ hoje ≤ dueDate} e {@code outstanding > 0}. */
    CLOSED,
    /** {@code hoje > dueDate} e {@code outstanding > 0}. */
    OVERDUE,
    /** {@code closingDate ≤ hoje} e {@code outstanding = 0}. */
    PAID;

    /** "Fatura fechada" = {@code CLOSED}, {@code OVERDUE} ou {@code PAID}. */
    public boolean isClosed() {
        return this == CLOSED || this == OVERDUE || this == PAID;
    }
}
