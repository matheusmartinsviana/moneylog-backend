package com.moneylog.infra.simulation.controller;

import java.util.List;
import java.util.Map;

import com.moneylog.core.finance.domain.OwnerIncome;
import com.moneylog.core.finance.domain.PurchaseSimulation;
import com.moneylog.core.sk.money.Money;

public record PurchaseSimulationResponse(Money installmentAmount, Money total, List<MonthResponse> months, HorizonResponse horizon,
                                         PurchaseSimulation.Classification classification, List<ReasonResponse> reasons,
                                         Money endBalanceMin, Money endBalanceMinWithCuts, boolean financingWarning) {

    public record MonthResponse(String competence, Money surplusBefore, Money surplusAfter, Money delta, boolean negativeAfter,
                                Money cutsMonthly, Money surplusAfterWithCuts, Boolean negativeAfterWithCuts,
                                OwnerIncome.Confidence confidence) {
    }

    public record HorizonResponse(String from, String to, int calculatedMonths, int requiredMonths, boolean limited) {
    }

    public record ReasonResponse(String code, String competence, Map<String, String> params) {
    }

    public static PurchaseSimulationResponse of(PurchaseSimulation s) {
        return new PurchaseSimulationResponse(s.installmentAmount(), s.total(),
                s.months().stream().map(m -> new MonthResponse(m.competence().toString(), m.surplusBefore(), m.surplusAfter(), m.delta(),
                        m.negativeAfter(), m.cutsMonthly(), m.surplusAfterWithCuts(), m.negativeAfterWithCuts(), m.confidence())).toList(),
                new HorizonResponse(s.horizon().from().toString(), s.horizon().to().toString(), s.horizon().calculatedMonths(),
                        s.horizon().requiredMonths(), s.horizon().limited()),
                s.classification(),
                s.reasons().stream().map(r -> new ReasonResponse(r.code(), r.competence() == null ? null : r.competence().toString(),
                        r.params())).toList(),
                s.endBalanceMin(), s.endBalanceMinWithCuts(), s.financingWarning());
    }
}
