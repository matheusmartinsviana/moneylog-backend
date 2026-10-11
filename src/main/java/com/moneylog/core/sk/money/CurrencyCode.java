package com.moneylog.core.sk.money;

import java.util.Currency;

import com.moneylog.core.sk.money.MoneyExceptions.UnsupportedCurrencyException;

public record CurrencyCode(String value) {

    public static final CurrencyCode BRL = new CurrencyCode("BRL");

    public CurrencyCode {
        if (value == null || !value.matches("[A-Z]{3}")) {
            throw new UnsupportedCurrencyException(String.valueOf(value));
        }
        Currency currency;
        try {
            currency = Currency.getInstance(value);
        } catch (IllegalArgumentException e) {
            throw new UnsupportedCurrencyException(value);
        }
        if (currency.getDefaultFractionDigits() != Money.SCALE) {
            throw new UnsupportedCurrencyException(value);
        }
    }

    public static CurrencyCode of(String value) {
        return new CurrencyCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
