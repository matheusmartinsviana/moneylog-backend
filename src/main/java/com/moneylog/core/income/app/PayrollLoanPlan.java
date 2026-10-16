package com.moneylog.core.income.app;

import java.util.UUID;

import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileInvalidException;
import com.moneylog.core.sk.money.Money;

public record PayrollLoanPlan(UUID debtId, Money applied, Money interest) {

    public PayrollLoanPlan {
        if (debtId == null) {
            throw new IncomeProfileInvalidException("o consignado precisa apontar para uma dívida.");
        }
        if (applied == null || !applied.isPositive()) {
            throw new IncomeProfileInvalidException("a parcela do consignado precisa ser maior que zero.");
        }
        if (interest != null && (interest.isNegative() || !interest.isLessThan(applied))) {
            throw new IncomeProfileInvalidException("os juros da parcela precisam ficar entre zero e a parcela.");
        }
    }
}
