package com.moneylog.core.notification.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.sk.money.Money;

/**
 * Tudo que os geradores leem, já calculado pelas fontes únicas (PR 01, regra 10): compromissos do {@link CommitmentSchedule}, metas, lembretes
 * abertos e os números do dashboard. Os geradores são puros e determinísticos.
 *
 * @param available     disponível do dashboard
 * @param committed7d   comprometido em 7 dias (a mesma fonte do dashboard)
 */
public record GenerationContext(UUID userId, LocalDate today, LocalTime localTime, NotificationSettings settings, CommitmentSchedule schedule,
                                List<Goal> goals, List<Reminder> openReminders, Money available, Money committed7d) {

    public GenerationContext {
        goals = List.copyOf(goals);
        openReminders = List.copyOf(openReminders);
    }

    /** Identidades já gravadas hoje (idempotência) ficam fora do contexto: quem filtra é o {@link NotificationPlanner}. */
    public static Set<String> noIdentities() {
        return Set.of();
    }
}
