package com.moneylog.infra.projection.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.moneylog.core.finance.domain.Projection;
import com.moneylog.core.sk.money.Money;

/**
 * {@code Projection} do contrato (S-152): sempre {@code isProjection = true}, com as premissas ({@code assumptions}, chaves
 * traduzíveis). A tendência ({@code trend}) só vem com {@code includeTrend = true} e é sempre {@code estimate = true}.
 */
public record ProjectionResponse(@JsonProperty("isProjection") boolean isProjection, ProjectionHorizon horizon, LocalDate asOf,
                                 Money startingBalance, List<Point> series, List<String> assumptions, Goals goals,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) Trend trend) {

    public record Point(String competence, LocalDate periodEnd, Money inflows, Money outflows, Money balance) {
    }

    public record GoalInfo(UUID goalId, String name, Money current, Money target) {
    }

    public record Goals(Money reserved, List<GoalInfo> items) {
    }

    public record TrendPoint(String competence, Money estimatedSpending, Money balance) {
    }

    /** {@code reason}, {@code monthlyAverage} e {@code series} só quando aplicável ({@code available}). */
    public record Trend(boolean available, boolean estimate, String reason, Money monthlyAverage, List<String> basedOn,
                        List<TrendPoint> series) {
    }

    public static ProjectionResponse of(Projection p, ProjectionHorizon horizon) {
        Trend trend = null;
        if (p.trend() != null) {
            Projection.Trend t = p.trend();
            trend = new Trend(t.available(), t.estimate(), t.reason(), t.monthlyAverage(),
                    t.basedOn().stream().map(Object::toString).toList(),
                    t.series().stream().map(x -> new TrendPoint(x.competence().toString(), x.estimatedSpending(), x.balance())).toList());
        }
        return new ProjectionResponse(p.isProjection(), horizon, p.asOf(), p.startingBalance(),
                p.series().stream().map(x -> new Point(x.competence().toString(), x.periodEnd(), x.inflows(), x.outflows(), x.balance())).toList(),
                p.assumptions(), new Goals(p.goals().reserved(), p.goals().items().stream()
                .map(g -> new GoalInfo(g.goalId(), g.name(), g.current(), g.target())).toList()), trend);
    }
}
