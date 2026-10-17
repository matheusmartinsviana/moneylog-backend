package com.moneylog.core.income.domain;

import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileInvalidException;

public record PayrollAdvance(int dayOfMonth, int percentBps) {

    public PayrollAdvance {
        if (dayOfMonth < 1 || dayOfMonth > IncomeProfile.MAX_DAY) {
            throw new IncomeProfileInvalidException("o dia do adiantamento vai de 1 a " + IncomeProfile.MAX_DAY + ".");
        }
        if (percentBps < 0 || percentBps > 10_000) {
            throw new IncomeProfileInvalidException("o percentual do adiantamento vai de 0 a 10000 bps.");
        }
    }
}
