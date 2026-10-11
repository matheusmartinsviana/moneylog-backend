package com.moneylog.core.goal.util;

import static com.moneylog.core.util.Ids.USER_ID;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.sk.money.Money;

public final class GoalTestFactory {

    public static final UUID GOAL_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");
    public static final UUID SECOND_GOAL_ID = UUID.fromString("00000000-0000-0000-0000-00000000e002");
    public static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    public static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    public static final LocalDate DEADLINE = LocalDate.of(2027, 12, 31);

    private GoalTestFactory() {
    }

    public static Goal umaMetaDeEmergencia() {
        return Goal.create(GOAL_ID, USER_ID, "Reserva de emergência", Money.of("20000.00"), DEADLINE, GoalPriority.HIGH,
                "Seis meses de custo fixo");
    }

    public static Goal umaMeta(UUID id, String name, String target, LocalDate deadline, GoalPriority priority) {
        return Goal.create(id, USER_ID, name, Money.of(target), deadline, priority, null);
    }

    public static Instant at(int second) {
        return NOW.plusSeconds(second);
    }
}
