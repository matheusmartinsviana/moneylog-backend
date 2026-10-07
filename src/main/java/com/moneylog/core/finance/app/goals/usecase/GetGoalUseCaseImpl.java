package com.moneylog.core.finance.app.goals.usecase;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalViews;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.sk.time.TimeProvider;

public class GetGoalUseCaseImpl implements GetGoalUseCase {

    private final GoalGateway goals;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public GetGoalUseCaseImpl(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time) {
        this.goals = goals;
        this.records = records;
        this.time = time;
    }

    @Override
    public GoalDetails handle(GetGoalQuery query) {
        return goals.findById(query.userId(), query.goalId())
                .map(goal -> GoalViews.of(goal, records.load(query.userId()), time.today()))
                .orElseThrow(() -> new GoalNotFoundException(query.goalId()));
    }
}
