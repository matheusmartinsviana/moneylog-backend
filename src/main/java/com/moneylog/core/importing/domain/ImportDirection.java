package com.moneylog.core.importing.domain;

/**
 * Direção da linha do extrato do ponto de vista da conta/cartão importado.
 */
public enum ImportDirection {
    /** Entrada (crédito na conta). */
    IN,
    /** Saída (débito na conta, compra no cartão). */
    OUT
}
