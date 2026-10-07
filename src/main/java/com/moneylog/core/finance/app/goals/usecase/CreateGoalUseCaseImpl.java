package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalViews;
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

    public CreateGoalUseCaseImpl(GoalGateway goals, FinancialRecordsGateway records, IdempotencyGateway idempotency, IdGenerator ids,
                                 TimeProvider time) {
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
                return GoalViews.of(current, records.load(c.userId()), time.today());
            }
        }
        GoalPriority priority = c.priority() == null ? GoalPriority.MEDIUM : c.priority();
        Goal goal;
        if (c.kind() == GoalKind.DEBT_PAYOFF) {
            boolean exists = goals.findAll(c.userId()).stream().map(Versioned::value).anyMatch(g -> g.isDebtPayoff() && !g.isArchived());
            if (exists) {
                throw new DebtPayoffGoalExistsException();
            }
            String name = c.name() == null || c.name().isBlank() ? DEBT_PAYOFF_DEFAULT_NAME : c.name();
            goal = Goal.createDebtPayoff(goalId, c.userId(), name, c.deadline(), priority, c.description());
        } else {
            goal = Goal.create(goalId, c.userId(), c.name(), c.target(), c.deadline(), priority, c.description());
        }
        Versioned<Goal> saved = goals.save(goal);
        return GoalViews.of(saved, records.load(c.userId()), time.today());
    }

    private static String hashOf(CreateGoalCommand c) {
        return IdempotentClaim.hash("name=" + nz(c.name()).strip() + ";target=" + (c.target() == null ? "-" : c.target().toPlainString())
                + ";kind=" + nz(c.kind()) + ";deadline=" + nz(c.deadline()) + ";priority=" + nz(c.priority()) + ";description=" + nz(c.description()).strip() + ";");
    }

    private static String nz(Object value) {
        return value == null ? "-" : value.toString();
    }
}
