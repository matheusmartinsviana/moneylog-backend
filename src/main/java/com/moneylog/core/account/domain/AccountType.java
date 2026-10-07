package com.moneylog.core.account.domain;

/**
 * Tipo de conta (D1 §2.2). Cartão de crédito não é conta: é a entidade {@code CreditCard}.
 */
public enum AccountType {
    /** Conta corrente. */
    CHECKING(LiquidityClass.CASH),
    /** Poupança. */
    SAVINGS(LiquidityClass.CASH),
    /** Carteira (dinheiro físico). */
    WALLET(LiquidityClass.CASH),
    /** Conta de investimento. */
    INVESTMENT(LiquidityClass.INVESTMENT);

    private final LiquidityClass liquidity;

    AccountType(LiquidityClass liquidity) {
        this.liquidity = liquidity;
    }

    public LiquidityClass liquidity() {
        return liquidity;
    }
}
