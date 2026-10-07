package com.moneylog.core.account.domain;

/**
 * Classe de liquidez da conta (D1 §2.2). Transferência só entre contas da mesma classe; entre classes é investimento.
 */
public enum LiquidityClass {
    CASH,
    INVESTMENT
}
