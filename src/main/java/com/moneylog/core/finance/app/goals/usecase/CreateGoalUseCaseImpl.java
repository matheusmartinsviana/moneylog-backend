package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalReadModel;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.goal.exception.GoalExceptions.GoalFieldRequiredException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalMetricDuplicateException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalKind;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.goal.exception.GoalExceptions.DebtPayoffGoalExistsException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;

public class CreateGoalUseCaseImpl implements CreateGoalUseCase {

    public static final String DEBT_PAYOFF_DEFAULT_NAME = "Quitar todas as dívidas";

    private final GoalGateway goals;
    private final FinancialRecordsGateway records;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final GoalReadModel model;

    public CreateGoalUseCaseImpl(GoalGateway goals, FinancialRecordsGateway records, IdempotencyGateway idempotency, IdGenerator ids,
                                 TimeProvider time, GoalReadModel model) {
        this.model = model;
        this.goals = goals;
        this.records = records;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public GoalDetails handle(CreateGoalCommand c) {
        UUID goalId = ids.next();
        if (c.idempotencyKey() != null) {
            String hash = hashOf(c);
            Claim existing = IdempotentClaim.claimOrFind(idempotency, c.userId(), IDEMPOTENCY_SCOPE, c.idempotencyKey(), hash, goalId, time.now());
            if (existing != null) {
                IdempotentClaim.requireSameCommand(existing, hash);
                // o replay devolve o estado ATUAL (S-87)
                Versioned<Goal> current = goals.findById(c.userId(), existing.resourceId())
                        .orElseThrow(() -> new GoalNotFoundException(existing.resourceId()));
                return model.view(c.userId(), current, records.load(c.userId()));
            }
        }
        GoalPriority priority = c.priority() == null ? GoalPriority.MEDIUM : c.priority();
        FinancialRecords loaded = null;
        Money baseline = null;
        Goal goal;
        if (c.kind() == GoalKind.DEBT_PAYOFF) {
            boolean exists = goals.findAll(c.userId()).stream().map(Versioned::value).anyMatch(g -> g.isDebtPayoff() && !g.isArchived());
            if (exists) {
                throw new DebtPayoffGoalExistsException();
            }
            String name = c.name() == null || c.name().isBlank() ? DEBT_PAYOFF_DEFAULT_NAME : c.name();
            goal = Goal.createDebtPayoff(goalId, c.userId(), name, c.deadline(), priority, c.description());
        } else if (c.kind() == GoalKind.METRIC) {
            if (c.metric() == null) {
                throw new GoalFieldRequiredException("metric");
            }
            if (c.target() == null) {
                throw new GoalFieldRequiredException("target");
            }
            boolean duplicate = goals.findAll(c.userId()).stream().map(Versioned::value)
                    .anyMatch(g -> g.isMetric() && !g.isArchived() && g.metric() == c.metric() && g.target().equals(c.target()));
            if (duplicate) {
                throw new GoalMetricDuplicateException();
            }
            // o ponto de partida é o valor atual da medida, na mesma fonte das leituras (sem o passo "montando")
            loaded = records.load(c.userId());
            baseline = model.metricValue(c.userId(), c.metric(), loaded);
            goal = Goal.createMetric(goalId, c.userId(), c.name(), c.metric(), c.target(), baseline, time.now(), c.deadline(), priority,
                    c.description());
        } else {
            goal = Goal.create(goalId, c.userId(), c.name(), c.target(), c.deadline(), priority, c.description());
        }
        Versioned<Goal> saved = goals.save(goal);
        if (baseline != null) {
            model.recordSnapshot(c.userId(), goal, baseline); // histórico começa na criação
        }
        return model.view(c.userId(), saved, loaded == null ? records.load(c.userId()) : loaded);
    }

    private static String hashOf(CreateGoalCommand c) {
        return IdempotentClaim.hash("name=" + nz(c.name()).strip() + ";target=" + (c.target() == null ? "-" : c.target().toPlainString())
                + ";kind=" + nz(c.kind()) + ";metric=" + nz(c.metric()) + ";deadline=" + nz(c.deadline()) + ";priority=" + nz(c.priority()) + ";description=" + nz(c.description()).strip() + ";");
    }

    private static String nz(Object value) {
        return value == null ? "-" : value.toString();
    }
}
