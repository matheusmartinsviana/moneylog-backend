package com.moneylog.core.finance.app.cards;

import java.util.Objects;

import com.moneylog.core.sk.money.Money;

/**
 * Prévia do pagamento de uma fatura (S-136): o que o pagamento deixaria, sem gravar. A tela nunca subtrai valores.
 *
 * @param kind              {@code FULL} quita o {@code outstanding}; {@code PARTIAL} paga menos; {@code OVERPAY} paga mais numa
 *                          fatura fechada (o excedente vira crédito transportado); {@code PREPAY} é qualquer pagamento
 *                          numa fatura {@code OPEN} (antecipação, S-15)
 * @param outstandingAfter  {@code outstanding} da fatura depois do pagamento
 * @param creditCarryAfter  crédito transportado da fatura depois do pagamento
 */
public record PaymentPreviewView(Kind kind, Money outstandingAfter, Money creditCarryAfter) {

    public enum Kind {
        FULL,
        PARTIAL,
        OVERPAY,
        PREPAY
    }

    public PaymentPreviewView {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(outstandingAfter, "outstandingAfter");
        Objects.requireNonNull(creditCarryAfter, "creditCarryAfter");
    }
}
