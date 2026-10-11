package com.moneylog.infra.goal.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalAction;
import com.moneylog.core.goal.domain.GoalActionFrequency;
import com.moneylog.core.goal.domain.GoalConsistency;
import com.moneylog.core.goal.domain.GoalDirection;
import com.moneylog.core.goal.domain.GoalKind;
import com.moneylog.core.goal.domain.GoalMetric;
import com.moneylog.core.goal.domain.GoalPhoto;
import com.moneylog.core.goal.domain.GoalPlan;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.goal.domain.GoalReservation;
import com.moneylog.core.goal.domain.GoalStage;
import com.moneylog.core.goal.domain.GoalStatus;
import com.moneylog.core.sk.money.Money;

public record GoalResponse(UUID id, GoalKind kind, String name, Money target, Money current, String progress, Money remaining, GoalStatus status,
                           LocalDate deadline, GoalPriority priority, String description, Money monthlyNeeded, boolean uncovered,
                           List<ReservationResponse> reservations, boolean baselineSet, Money baseline, Instant baselineAt, boolean grewSinceBaseline,
                           GoalMetric metric, GoalDirection direction, boolean reached, LocalDate metricAsOf,
                           GoalStage stage, Money requiredPace, Integer monthsLeft, Money nextMilestone, Boolean onTrack, Money gap,
                           String planNote, List<ActionResponse> actions, List<ConsistencyMonth> consistency, int streak,
                           String consistencyRate, boolean featured, UUID coverPhotoId, List<PhotoResponse> photos, long version,
                           Instant createdAt, Instant updatedAt) {

    public record PhotoResponse(UUID id, int position, int width, int height, Instant createdAt) {
    }


    public record ReservationResponse(UUID id, AccountRef account, Money amount, LocalDate date, String note, Instant createdAt) {
    }

    public record AccountRef(UUID id, String name) {
    }

    public record ActionResponse(UUID id, String title, GoalActionFrequency frequency, List<String> doneIn, Instant createdAt) {
    }

    public record ConsistencyMonth(String month, Money expected, Money actual, Boolean met, boolean closed) {
    }

    public static GoalResponse of(GoalDetails details) {
        Goal goal = details.goal();
        List<ReservationResponse> reservations = details.reservations().stream().map(view -> {
            GoalReservation r = view.reservation();
            return new ReservationResponse(r.id(), new AccountRef(r.accountId(), view.accountName()), r.amount(), r.date(), r.note(),
                    r.createdAt());
        }).toList();
        GoalDetails.PayoffInfo payoff = details.payoff();
        GoalDetails.MetricInfo metric = details.metric();
        GoalDetails.Planning planning = details.planning();
        GoalPlan plan = planning.plan();
        GoalConsistency consistency = planning.consistency();
        Money current = metric != null ? metric.current() : details.current();
        Money remaining = payoff != null ? payoff.remaining() : metric != null ? metric.remaining() : details.progress().remaining();
        boolean reached = metric != null ? metric.reached() : details.status() == GoalStatus.ACHIEVED;
        return new GoalResponse(goal.id(), goal.kind(), goal.name(), goal.target(), current, details.progress().ratioText(), remaining,
                details.status(), goal.deadline(), goal.priority(), goal.description(), details.monthlyNeeded(), details.uncovered(),
                reservations, payoff != null ? payoff.baselineSet() : goal.isMetric(), goal.baseline(), goal.baselineAt(),
                payoff != null && payoff.grewSinceBaseline(), goal.metric(), goal.direction(), reached,
                metric == null ? null : metric.asOf(), planning.stage(), plan == null ? null : plan.requiredPace(),
                plan == null ? null : plan.monthsLeft(), plan == null ? null : plan.nextMilestone(), plan == null ? null : plan.onTrack(),
                plan == null ? null : plan.gap(), planning.planNote(), planning.actions().stream().map(GoalResponse::action).toList(),
                consistency.history().stream().map(m -> new ConsistencyMonth(m.month().toString(), m.expected(), m.actual(), m.met(), m.closed())).toList(),
                consistency.streak(), rate(consistency.rate()), goal.isFeatured(), goal.coverPhotoId(), photos(goal), details.version(), details.createdAt(), details.updatedAt());
    }

    public static List<PhotoResponse> photos(Goal goal) {
        List<GoalPhoto> photos = goal.photos();
        List<PhotoResponse> out = new java.util.ArrayList<>(photos.size());
        for (int i = 0; i < photos.size(); i++) {
            out.add(photo(photos.get(i), i));
        }
        return out;
    }

    public static PhotoResponse photo(GoalPhoto p, int position) {
        return new PhotoResponse(p.id(), position, p.width(), p.height(), p.createdAt());
    }

    private static ActionResponse action(GoalAction a) {
        return new ActionResponse(a.id(), a.title(), a.frequency(), List.copyOf(a.doneIn()), a.createdAt());
    }

    private static String rate(BigDecimal rate) {
        return rate == null ? null : rate.toPlainString();
    }
}
