package com.moneylog.core.income.app;

import java.util.List;

import com.moneylog.core.income.domain.PayrollAdvance;
import com.moneylog.core.income.domain.PayrollDeduction;
import com.moneylog.core.income.domain.ThirteenthSettings;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;

public record IncomeProfileSpec(String source, Money gross, int dependents, PayrollAdvance advance, int balanceDayOfMonth,
                                List<PayrollDeduction> deductions, ThirteenthSettings thirteenth, Provenance provenance) {

    public IncomeProfileSpec {
        deductions = deductions == null ? List.of() : List.copyOf(deductions);
    }
}
