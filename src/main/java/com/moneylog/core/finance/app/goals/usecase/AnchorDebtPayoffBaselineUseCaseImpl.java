package com.moneylog.core.finance.app.goals.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalViews;
import com.moneylog.core.finance.domain.DebtOverviewCalculator;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class AnchorDebtPayoffBaselineUseCaseImpl implements AnchorDebtPayoffBaselineUseCase {

    private final GoalGateway goals;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public AnchorDebtPayoffBaselineUseCaseImpl(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time) {
        this.goals = goals;
        this.records = records;
        this.time = time;
    }

    @Override
    public GoalDetails handle(AnchorDebtPayoffBaselineCommand command) {
        goals.lockForUpdate(command.userId(), command.goalId());
        Goal goal = goals.findById(command.userId(), command.goalId()).map(Versioned::value)
                .orElseThrow(() -> new GoalNotFoundException(command.goalId()));
        FinancialRecords loaded = records.load(command.userId());
        // o mesmo total do GET /v1/debts/overview: a baseline e o progresso saem da mesma conta
        goal.anchorBaseline(DebtOverviewCalculator.total(loaded, time.today()), time.now());
        Versioned<Goal> saved = goals.save(goal);
        return GoalViews.of(saved, loaded, time.today());
    }
}
