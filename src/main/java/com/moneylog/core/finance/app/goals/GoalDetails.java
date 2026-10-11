package com.moneylog.core.finance.app.goals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalAction;
import com.moneylog.core.goal.domain.GoalConsistency;
import com.moneylog.core.goal.domain.GoalPlan;
import com.moneylog.core.goal.domain.GoalProgress;
import com.moneylog.core.goal.domain.GoalReservation;
import com.moneylog.core.goal.domain.GoalStage;
import com.moneylog.core.goal.domain.GoalStatus;
import com.moneylog.core.sk.money.Money;

public record GoalDetails(Goal goal, long version, Instant createdAt, Instant updatedAt, LocalDate asOf, GoalProgress progress,
                          GoalStatus status, Money monthlyNeeded, boolean uncovered, List<ReservationView> reservations,
                          PayoffInfo payoff, MetricInfo metric, Planning planning) {

    public record PayoffInfo(boolean baselineSet, Money baseline, Instant baselineAt, boolean grewSinceBaseline, Money remaining) {
    }

    public record MetricInfo(Money current, Money remaining, boolean reached, LocalDate asOf) {
    }

    public record Planning(GoalStage stage, GoalPlan plan, String planNote, List<GoalAction> actions, GoalConsistency consistency) {

        public Planning {
            actions = List.copyOf(actions);
        }
    }

    public GoalDetails {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(planning, "planning");
        reservations = List.copyOf(reservations);
    }

    public Money current() {
        return progress.current();
    }

    public record ReservationView(GoalReservation reservation, String accountName) {
    }
}
