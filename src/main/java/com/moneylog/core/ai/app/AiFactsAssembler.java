package com.moneylog.core.ai.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moneylog.core.ai.app.usecase.AiUseCases.InsightsView;
import com.moneylog.core.ai.domain.AiFacts;
import com.moneylog.core.insights.domain.Insight;
import com.moneylog.core.ai.domain.Redactor;
import com.moneylog.core.finance.domain.MonthlySummary;

public final class AiFactsAssembler {

    public record Assembled(AiFacts facts, Set<String> redactions) {
    }

    private static final Set<String> TEXT_PARAMS = Set.of("categoryName", "description", "cardName", "goalName");

    private AiFactsAssembler() {
    }

    public static Assembled assemble(InsightsView view, Redactor redactor) {
        MonthlySummary s = view.summary();
        Set<String> redactions = new LinkedHashSet<>();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("month", s.competence().toString());
        facts.put("partial", String.valueOf(s.partial()));
        facts.put("income", s.income().toPlainString());
        facts.put("expenses", s.expenses().toPlainString());
        facts.put("net", s.net().toPlainString());
        facts.put("investments", s.investments().toPlainString());
        if (s.savingsRate() != null) {
            facts.put("savingsRatePercent", percent(s.savingsRate()));
        }
        facts.put("cardConsumption", s.card().consumption().toPlainString());
        facts.put("cardOutstanding", s.card().outstanding().toPlainString());
        facts.put("futureInstallments", s.futureInstallments().toPlainString());
        facts.put("expensesVsPreviousAmount", s.comparison().expenses().amount().toPlainString());
        if (s.comparison().expenses().percent() != null) {
            facts.put("expensesVsPreviousPercent", percent(s.comparison().expenses().percent()));
        }
        if (s.largestCategory() != null) {
            String name = view.categoryNames().get(s.largestCategory().categoryId());
            facts.put("largestCategoryName", redact(redactor, name, redactions));
            facts.put("largestCategoryAmount", s.largestCategory().amount().toPlainString());
        }
        int index = 1;
        for (Insight insight : view.insights()) {
            String prefix = "insight." + index++ + ".";
            facts.put(prefix + "type", insight.type());
            for (Map.Entry<String, String> param : insight.params().entrySet()) {
                if (param.getKey().equals("categoryId")) {
                    continue;
                }
                String value = param.getValue();
                facts.put(prefix + param.getKey(), TEXT_PARAMS.contains(param.getKey()) ? redact(redactor, value, redactions) : value);
            }
        }
        facts.put("insightCount", String.valueOf(view.insights().size()));
        return new Assembled(new AiFacts(facts), Set.copyOf(redactions));
    }

    private static String redact(Redactor redactor, String text, Set<String> redactions) {
        Redactor.Result result = redactor.redact(text);
        redactions.addAll(result.types());
        return result.text();
    }

    private static String percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static List<String> sorted(Set<String> redactions) {
        return redactions.stream().sorted().toList();
    }
}
