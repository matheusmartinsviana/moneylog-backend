package com.moneylog.core.notification.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.sk.money.Money;

public record GenerationContext(UUID userId, LocalDate today, LocalTime localTime, NotificationSettings settings, CommitmentSchedule schedule,
                                List<Goal> goals, List<Reminder> openReminders, Money available, Money committed7d) {

    public GenerationContext {
        goals = List.copyOf(goals);
        openReminders = List.copyOf(openReminders);
    }

    public static Set<String> noIdentities() {
        return Set.of();
    }
}
