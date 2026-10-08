package com.moneylog.core.goal.domain;

/** Frequência de uma ação do plano declarado; define o formato do período do check-in. */
public enum GoalActionFrequency {
    /** Período {@code YYYY-MM}. */
    MONTHLY,
    /** Período ISO {@code YYYY-Www}. */
    WEEKLY,
    /** Período único {@code ONCE}. */
    ONE_TIME
}
