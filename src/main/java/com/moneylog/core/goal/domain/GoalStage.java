package com.moneylog.core.goal.domain;

public enum GoalStage {
    FANTASY,
    OBJECTIVE,
    STRATEGY,
    CONSISTENT,
    ACHIEVED;

    public static final int CONSISTENT_STREAK = 2;

    public static GoalStage derive(boolean achieved, boolean hasDeadline, boolean hasAutoPlan, boolean declaredPlan, int streak) {
        if (achieved) {
            return ACHIEVED;
        }
        if (!hasDeadline) {
            return FANTASY;
        }
        if (!hasAutoPlan || !declaredPlan) {
            return OBJECTIVE;
        }
        return streak >= CONSISTENT_STREAK ? CONSISTENT : STRATEGY;
    }
}
