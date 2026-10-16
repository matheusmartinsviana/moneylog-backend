package com.moneylog.core.finance.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.moneylog.core.finance.domain.PurchaseSimulation.Classification;
import com.moneylog.core.finance.domain.PurchaseSimulation.Horizon;
import com.moneylog.core.finance.domain.PurchaseSimulation.MonthImpact;
import com.moneylog.core.finance.domain.PurchaseSimulation.Reason;

public final class PurchaseClassifier {

    public static final String NEGATIVE_EVEN_WITH_CUTS = "NEGATIVE_EVEN_WITH_CUTS";
    public static final String NEGATIVE_WITHOUT_CUTS = "NEGATIVE_WITHOUT_CUTS";
    public static final String NEGATIVE_FIXED_WITH_CUTS = "NEGATIVE_FIXED_WITH_CUTS";
    public static final String FINANCING_PENDING = "FINANCING_PENDING";
    public static final String ESTIMATED_INCOME_NEEDED = "ESTIMATED_INCOME_NEEDED";
    public static final String HORIZON_LIMITED = "HORIZON_LIMITED";
    public static final String NO_NEGATIVE_MONTH = "NO_NEGATIVE_MONTH";

    public record Verdict(Classification classification, List<Reason> reasons) {
    }

    private PurchaseClassifier() {
    }

    public static Verdict classify(List<MonthImpact> months, Horizon horizon, boolean financingWarning) {
        boolean withCuts = months.stream().anyMatch(MonthImpact::hasCuts);
        List<Reason> reasons = new ArrayList<>();
        Classification classification;
        if (withCuts && months.stream().anyMatch(m -> Boolean.TRUE.equals(m.negativeAfterWithCuts()))) {
            months.stream().filter(m -> Boolean.TRUE.equals(m.negativeAfterWithCuts()))
                    .forEach(m -> reasons.add(new Reason(NEGATIVE_EVEN_WITH_CUTS, m.competence(), Map.of())));
            classification = Classification.INCOMPATIBLE;
        } else if (!withCuts && months.stream().anyMatch(MonthImpact::negativeAfter)) {
            months.stream().filter(MonthImpact::negativeAfter)
                    .forEach(m -> reasons.add(new Reason(NEGATIVE_WITHOUT_CUTS, m.competence(), Map.of())));
            classification = Classification.INCOMPATIBLE;
        } else if (months.stream().anyMatch(MonthImpact::negativeAfter)) {
            months.stream().filter(MonthImpact::negativeAfter)
                    .forEach(m -> reasons.add(new Reason(NEGATIVE_FIXED_WITH_CUTS, m.competence(), Map.of())));
            classification = Classification.VIABLE_WITH_PLANNING;
        } else if (financingWarning || horizon.limited() || months.stream().anyMatch(MonthImpact::negativeWithoutEstimatedIncome)) {
            months.stream().filter(MonthImpact::negativeWithoutEstimatedIncome)
                    .forEach(m -> reasons.add(new Reason(ESTIMATED_INCOME_NEEDED, m.competence(), Map.of())));
            classification = Classification.RISKY;
        } else {
            reasons.add(new Reason(NO_NEGATIVE_MONTH, null, Map.of()));
            classification = Classification.SUSTAINABLE;
        }
        if (financingWarning) {
            reasons.add(new Reason(FINANCING_PENDING, null, Map.of()));
        }
        if (horizon.limited()) {
            reasons.add(new Reason(HORIZON_LIMITED, null, Map.of("calculatedMonths", String.valueOf(horizon.calculatedMonths()),
                    "requiredMonths", String.valueOf(horizon.requiredMonths()))));
        }
        return new Verdict(classification, reasons);
    }
}
