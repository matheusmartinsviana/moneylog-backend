package com.moneylog.core.card.domain;

/**
 * Status da parcela (D1 §2.5.2): {@code PENDING}/{@code PAID}/{@code OVERDUE} derivados da fatura; só
 * {@code CANCELED} é gravado.
 */
public enum InstallmentStatus {
    PENDING,
    PAID,
    OVERDUE,
    CANCELED
}
