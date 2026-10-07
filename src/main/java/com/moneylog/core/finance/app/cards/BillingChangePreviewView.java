package com.moneylog.core.finance.app.cards;

import java.time.LocalDate;

/**
 * Prévia da mudança de fechamento/vencimento (S-128): a partir de qual fatura os dias novos valem, o que muda de fatura e o
 * aviso de S-42. A tela mostra; nunca calcula.
 *
 * @param effectiveFromClosingDate fechamento (no calendário <b>atual</b>) da primeira fatura afetada: o que o {@code PUT}
 *                                 devolve em {@code confirmedEffectiveFrom}. Muda quando a fatura corrente vira
 * @param firstNewClosingDate      fechamento da primeira fatura com a regra nova (calendário novo)
 * @param firstNewDueDate          vencimento dessa fatura
 * @param movedInstallments        parcelas ativas que mudam de fatura
 * @param movedCredits             créditos ativos (reembolso no cartão) que mudam de fatura
 */
public record BillingChangePreviewView(LocalDate effectiveFromClosingDate, LocalDate firstNewClosingDate, LocalDate firstNewDueDate,
                                       int movedInstallments, int movedCredits) {
}
