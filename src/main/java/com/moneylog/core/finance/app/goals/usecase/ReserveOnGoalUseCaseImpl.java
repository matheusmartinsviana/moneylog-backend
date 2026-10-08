package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalReadModel;
import com.moneylog.core.finance.app.goals.GoalViews;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.ReserveAllocation;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;

/**
 * Reservas da meta (T93, S-147/S-148). Ordem global de travas: a meta vem <b>antes</b> das contas (a lista de travas das Etapas 3 e
 * 4 não toca metas): duas reservas na mesma meta se serializam na trava da meta; conta arquivada concorrente espera na trava da
 * conta. Nada é gravado no razão nem em {@code transactions}: só {@code goal_reservations}.
 */
public class ReserveOnGoalUseCaseImpl implements ReserveOnGoalUseCase {

    private final GoalGateway goals;
    private final AccountGateway accounts;
    private final FinancialRecordsGateway records;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final GoalReadModel model;

    @SuppressWarnings("java:S107")
    public ReserveOnGoalUseCaseImpl(GoalGateway goals, AccountGateway accounts, FinancialRecordsGateway records,
                                    IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time,
                                    GoalReadModel model) {
        this.model = model;
        this.goals = goals;
        this.accounts = accounts;
        this.records = records;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public GoalDetails handle(ReserveOnGoalCommand c) {
        UUID userId = c.userId();
        UUID reservationId = ids.next();
        if (c.idempotencyKey() != null) {
            String hash = hashOf(c);
            Claim existing = IdempotentClaim.claimOrFind(idempotency, userId, IDEMPOTENCY_SCOPE, c.idempotencyKey(), hash, reservationId,
                    time.now());
            if (existing != null) {
                IdempotentClaim.requireSameCommand(existing, hash);
                Versioned<Goal> current = goals.findById(userId, c.goalId()).orElseThrow(() -> new GoalNotFoundException(c.goalId()));
                return model.view(userId, current, records.load(userId));
            }
        }
        goals.lockForUpdate(userId, c.goalId());
        Goal goal = goals.findById(userId, c.goalId()).map(Versioned::value).orElseThrow(() -> new GoalNotFoundException(c.goalId()));
        accounts.lockForUpdate(userId, c.accountId());
        Account account = accounts.findById(userId, c.accountId()).map(Persisted::value)
                .orElseThrow(() -> new AccountNotFoundException(c.accountId()));
        goal.reserve(reservationId, ReserveAllocation.of(account, c.amount()), c.date(), c.note(), time.now());
        Versioned<Goal> saved = goals.save(goal);
        return model.view(userId, saved, records.load(userId));
    }

    /** Hash do comando canônico (o dono não entra: a chave é por usuário). */
    private static String hashOf(ReserveOnGoalCommand c) {
        return IdempotentClaim.hash("goalId=" + c.goalId() + ";accountId=" + c.accountId() + ";amount="
                + (c.amount() == null ? "-" : c.amount().toPlainString()) + ";date=" + c.date() + ";note="
                + (c.note() == null ? "-" : c.note().strip()) + ";");
    }
}
