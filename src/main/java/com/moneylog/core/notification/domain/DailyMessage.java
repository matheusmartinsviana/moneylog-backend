package com.moneylog.core.notification.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalStatus;
import com.moneylog.core.sk.money.Money;

public record DailyMessage(String key, Map<String, String> params) {

    public static final String SUMMARY_KEY = "daily.summary";

    public DailyMessage {
        params = Map.copyOf(params);
    }

    public static DailyMessage of(String displayName, Money available, Money committed7d, List<Goal> goals, LocalDate today) {
        Map<String, String> params = new LinkedHashMap<>();
        if (displayName != null && !displayName.isBlank()) {
            params.put("name", displayName);
        }
        params.put("available", available.toPlainString());
        params.put("committed7d", committed7d.toPlainString());
        Optional<Goal> goal = goals.stream().filter(g -> !g.isMetric() && g.deadline() != null && g.status(today) == GoalStatus.ACTIVE)
                .min(Comparator.comparing(Goal::deadline).thenComparing(Goal::name).thenComparing(Goal::id));
        goal.ifPresent(g -> {
            params.put("goalName", g.name());
            params.put("goalProgress", g.progress(today).ratioText());
        });
        return new DailyMessage(SUMMARY_KEY, params);
    }
}
