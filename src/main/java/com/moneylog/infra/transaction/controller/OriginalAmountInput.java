package com.moneylog.infra.transaction.controller;

import java.math.BigDecimal;

import com.moneylog.core.recurrence.app.ForeignInput;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OriginalAmountInput(@NotNull @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,2})?$") String amount,
                                  @NotNull @Pattern(regexp = "^(BRL|USD|EUR)$") String currency) {

    public static final String FX_RATE_PATTERN = "^\\d{1,6}(\\.\\d{1,6})?$";

    public static ForeignInput foreign(OriginalAmountInput original, String fxRate, Integer iofBps) {
        return new ForeignInput(original == null ? null : Money.of(original.amount(), CurrencyCode.of(original.currency())),
                fxRate == null ? null : new BigDecimal(fxRate), iofBps);
    }
}
