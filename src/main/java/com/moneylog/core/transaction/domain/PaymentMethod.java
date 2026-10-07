package com.moneylog.core.transaction.domain;

/**
 * Meio de pagamento (D1 §2.4). {@code CREDIT} não existe: compra no crédito é sempre {@code CARD_PURCHASE}.
 * {@code TRANSFER} aqui é TED/DOC para terceiro, diferente do tipo {@code TRANSFER} entre contas próprias.
 */
public enum PaymentMethod {
    CASH,
    DEBIT,
    PIX,
    TRANSFER,
    BOLETO,
    OTHER
}
