package com.moneylog.core.account.domain;

public enum AccountType {
    CHECKING(LiquidityClass.CASH),
    SAVINGS(LiquidityClass.CASH),
    WALLET(LiquidityClass.CASH),
    INVESTMENT(LiquidityClass.INVESTMENT);

    private final LiquidityClass liquidity;

    AccountType(LiquidityClass liquidity) {
        this.liquidity = liquidity;
    }

    public LiquidityClass liquidity() {
        return liquidity;
    }
}
