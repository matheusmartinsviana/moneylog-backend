package com.moneylog.core.income.domain;

import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileInvalidException;

public record ThirteenthSettings(boolean enabled, int firstInstallmentMonth) {

    public static final int DEFAULT_FIRST_INSTALLMENT_MONTH = 11;

    public ThirteenthSettings {
        if (firstInstallmentMonth < 1 || firstInstallmentMonth > 12) {
            throw new IncomeProfileInvalidException("o mês da 1ª parcela do 13º vai de 1 a 12.");
        }
    }

    public static ThirteenthSettings disabled() {
        return new ThirteenthSettings(false, DEFAULT_FIRST_INSTALLMENT_MONTH);
    }
}
