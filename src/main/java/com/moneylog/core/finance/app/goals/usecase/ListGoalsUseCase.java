package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.goal.domain.GoalStatus;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListGoalsUseCase {

    Slice<GoalDetails> handle(ListGoalsQuery query);

    record ListGoalsQuery(UUID userId, GoalStatus status, Boolean featured, PageQuery page) {

        public ListGoalsQuery(UUID userId, GoalStatus status, PageQuery page) {
            this(userId, status, null, page);
        }
    }
}
