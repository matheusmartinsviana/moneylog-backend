package com.moneylog.infra.goal.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.goal.domain.GoalReservation;
import com.moneylog.core.goal.domain.GoalStatus;
import com.moneylog.core.sk.money.Money;

/**
 * {@code Goal} do contrato: tudo calculado pelo backend (D2 §4.7). {@code current}, {@code progress} (razão com 4 casas),
 * {@code remaining}, {@code status} derivado, {@code monthlyNeeded} (estimativa) e {@code uncovered} (S-148). Reserva lógica: nada aqui
 * é movimentação nem lançamento.
 */
public record GoalResponse(UUID id, String name, Money target, Money current, String progress, Money remaining, GoalStatus status,
                           LocalDate deadline, GoalPriority priority, String description, Money monthlyNeeded, boolean uncovered,
                           List<ReservationResponse> reservations, long version, Instant createdAt, Instant updatedAt) {

    /** {@code GoalReservation} do contrato. */
    public record ReservationResponse(UUID id, AccountRef account, Money amount, LocalDate date, String note, Instant createdAt) {
    }

    public record AccountRef(UUID id, String name) {
    }

    public static GoalResponse of(GoalDetails details) {
        Goal goal = details.goal();
        List<ReservationResponse> reservations = details.reservations().stream().map(view -> {
            GoalReservation r = view.reservation();
            return new ReservationResponse(r.id(), new AccountRef(r.accountId(), view.accountName()), r.amount(), r.date(), r.note(),
                    r.createdAt());
        }).toList();
        return new GoalResponse(goal.id(), goal.name(), goal.target(), details.current(), details.progress().ratioText(),
                details.progress().remaining(), details.status(), goal.deadline(), goal.priority(), goal.description(),
                details.monthlyNeeded(), details.uncovered(), reservations, details.version(), details.createdAt(), details.updatedAt());
    }
}
