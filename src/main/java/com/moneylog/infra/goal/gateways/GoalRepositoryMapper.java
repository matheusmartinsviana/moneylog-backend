package com.moneylog.infra.goal.gateways;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalReservation;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.infra.goal.persistence.GoalEntity;
import com.moneylog.infra.goal.persistence.GoalReservationEntity;

/** {@code Goal} ⇄ {@code GoalEntity} + {@code GoalReservationEntity}. A reidratação usa {@link Goal#restore}. */
public final class GoalRepositoryMapper {

    private GoalRepositoryMapper() {
    }

    public static Goal toDomain(GoalEntity e, Collection<GoalReservationEntity> reservations) {
        List<GoalReservation> mapped = reservations.stream()
                .sorted(Comparator.comparing(GoalReservationEntity::getCreatedAt).thenComparing(GoalReservationEntity::getId))
                .map(GoalRepositoryMapper::toDomain).toList();
        return Goal.restore(e.getId(), e.getUserId(), e.getName(), Money.ofCents(e.getTargetCents(), CurrencyCode.of(e.getCurrency())),
                e.getDeadline(), e.getPriority(), e.getDescription(), e.isArchived(), mapped);
    }

    public static Versioned<Goal> toVersioned(GoalEntity e, Collection<GoalReservationEntity> reservations) {
        return new Versioned<>(toDomain(e, reservations), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    public static GoalReservation toDomain(GoalReservationEntity r) {
        return new GoalReservation(r.getId(), r.getGoalId(), r.getAccountId(),
                Money.ofCents(r.getAmountCents(), CurrencyCode.of(r.getCurrency())), r.getReservedOn(), r.getNote(), r.getCreatedAt());
    }

    public static GoalEntity toEntity(Goal g, Instant now) {
        return new GoalEntity(g.id(), g.userId(), g.name(), g.target().cents(), g.target().currency().value(), g.deadline(),
                g.priority(), g.description(), g.isArchived(), now);
    }

    public static GoalReservationEntity toEntity(GoalReservation r, java.util.UUID userId) {
        return new GoalReservationEntity(r.id(), userId, r.goalId(), r.accountId(), r.amount().cents(), r.amount().currency().value(),
                r.date(), r.note(), r.createdAt());
    }
}
