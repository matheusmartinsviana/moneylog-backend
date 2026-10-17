package com.moneylog.core.transaction.domain;

import java.util.Objects;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.TransactionExceptions.PayrollLoanInvalidException;

public record PayrollLoanShare(Debt debt, Money applied, Money interest, Category interestCategory) {

    public PayrollLoanShare {
        Objects.requireNonNull(debt, "debt");
        if (applied == null || !applied.isPositive()) {
            throw new PayrollLoanInvalidException("a parcela do consignado precisa ser maior que zero.");
        }
        if (interest != null && (interest.isNegative() || !interest.isLessThan(applied))) {
            throw new PayrollLoanInvalidException("os juros da parcela precisam ficar entre zero e a parcela.");
        }
        if (interest != null && interest.isPositive() && interestCategory == null) {
            throw new PayrollLoanInvalidException("os juros da parcela precisam de uma categoria de despesa.");
        }
    }

    public Money interestOrZero() {
        return interest == null ? Money.zero(applied.currency()) : interest;
    }

    public Money principal() {
        return applied.subtract(interestOrZero());
    }
}
