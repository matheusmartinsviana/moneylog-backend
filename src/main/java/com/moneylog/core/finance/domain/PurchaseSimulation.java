package com.moneylog.core.finance.domain;

import java.util.List;
import java.util.Map;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record PurchaseSimulation(Money installmentAmount, Money total, List<MonthImpact> months, Horizon horizon,
                                 Classification classification, List<Reason> reasons, Money endBalanceMin, Money endBalanceMinWithCuts,
                                 boolean financingWarning) {

    public enum Classification {
        SUSTAINABLE,
        VIABLE_WITH_PLANNING,
        RISKY,
        INCOMPATIBLE
    }

    public record MonthImpact(Competence competence, Money surplusBefore, Money surplusAfter, Money delta, boolean negativeAfter,
                              Money cutsMonthly, Money surplusAfterWithCuts, Boolean negativeAfterWithCuts,
                              OwnerIncome.Confidence confidence, boolean negativeWithoutEstimatedIncome) {

        public boolean hasCuts() {
            return cutsMonthly != null;
        }
    }

    public record Horizon(Competence from, Competence to, int calculatedMonths, int requiredMonths, boolean limited) {
    }

    public record Reason(String code, Competence competence, Map<String, String> params) {

        public Reason {
            params = Map.copyOf(params);
        }
    }

    public PurchaseSimulation {
        months = List.copyOf(months);
        reasons = List.copyOf(reasons);
    }
}
