package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalViews;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;

public class CreateGoalUseCaseImpl implements CreateGoalUseCase {

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
        Goal goal = Goal.create(goalId, c.userId(), c.name(), c.target(), c.deadline(), c.priority(), c.description());
        Versioned<Goal> saved = goals.save(goal);
        return GoalViews.of(saved, records.load(c.userId()), time.today());
    }

    private static String hashOf(CreateGoalCommand c) {
        return IdempotentClaim.hash("name=" + nz(c.name()).strip() + ";target=" + (c.target() == null ? "-" : c.target().toPlainString())
                + ";deadline=" + nz(c.deadline()) + ";priority=" + nz(c.priority()) + ";description=" + nz(c.description()).strip() + ";");
    }

    private static String nz(Object value) {
        return value == null ? "-" : value.toString();
    }
}
