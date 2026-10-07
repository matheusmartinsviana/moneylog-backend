package com.moneylog.core.finance.app.goals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalProgress;
import com.moneylog.core.goal.domain.GoalReservation;
import com.moneylog.core.goal.domain.GoalStatus;
import com.moneylog.core.sk.money.Money;

/**
 * Meta com o que a API calcula (S-79, S-147 a S-150, S-156): valor atual, progresso, status derivado, estimativa mensal e a
 * reserva descoberta. O front só exibe.
 *
 * @param asOf          "hoje": data de referência dos cálculos (reservas com data posterior ainda não contam)
 * @param monthlyNeeded estimativa (S-156); {@code null} sem prazo ou com prazo vencido
 * @param uncovered     alguma conta com reserva desta meta tem mais reservado que saldo (S-148); só sinaliza
 * @param reservations  todas as reservas (inclusive as futuras), da mais recente para a mais antiga
 * @param payoff        só na meta {@code DEBT_PAYOFF} (ADR-013): baseline, "cresceu desde a baseline" e o restante (= total de dívidas
 *                      hoje); {@code null} na meta de guardar dinheiro
 */
public record GoalDetails(Goal goal, long version, Instant createdAt, Instant updatedAt, LocalDate asOf, GoalProgress progress,
                          GoalStatus status, Money monthlyNeeded, boolean uncovered, List<ReservationView> reservations,
                          PayoffInfo payoff) {

    /** Meta de guardar dinheiro (sem dados de quitação). */
    public GoalDetails(Goal goal, long version, Instant createdAt, Instant updatedAt, LocalDate asOf, GoalProgress progress,
                       GoalStatus status, Money monthlyNeeded, boolean uncovered, List<ReservationView> reservations) {
        this(goal, version, createdAt, updatedAt, asOf, progress, status, monthlyNeeded, uncovered, reservations, null);
    }

    /**
     * @param baselineSet       "cadastrei tudo" foi confirmado; antes disso a meta está "montando" e não tem percentual
     * @param grewSinceBaseline o total de dívidas passou da baseline (dívida nova depois de ancorar); nada é re-ancorado sozinho
     * @param remaining         o total de dívidas hoje (o que falta quitar)
     */
    public record PayoffInfo(boolean baselineSet, Money baseline, Instant baselineAt, boolean grewSinceBaseline, Money remaining) {
    }

    public GoalDetails {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(progress, "progress");
        reservations = List.copyOf(reservations);
    }

    public Money current() {
        return progress.current();
    }

    /** Reserva com o nome da conta já resolvido. */
    public record ReservationView(GoalReservation reservation, String accountName) {
    }
}
