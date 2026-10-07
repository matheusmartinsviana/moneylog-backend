package com.moneylog.core.transaction.domain;

/** Direção do {@code INVESTMENT}: aplicação (CASH → INVESTMENT) ou resgate (INVESTMENT → CASH). */
public enum InvestmentDirection {
    CONTRIBUTION,
    REDEMPTION
}
