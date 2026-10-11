package com.moneylog.core.finance.app.debts;

import java.math.BigDecimal;
import java.util.UUID;

import com.moneylog.core.finance.domain.DebtOverview;
import com.moneylog.core.sk.money.Money;

public record DebtOverviewView(DebtOverview overview, PayoffGoalRef payoffGoal,
                               java.util.List<com.moneylog.core.commitment.domain.TrackedCommitment> tracked) {

    public DebtOverviewView(DebtOverview overview, PayoffGoalRef payoffGoal) {
        this(overview, payoffGoal, java.util.List.of());
    }


    public record PayoffGoalRef(UUID goalId, boolean baselineSet, BigDecimal progress, Money remaining) {
    }
}
