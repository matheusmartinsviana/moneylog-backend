package com.moneylog.core.goal.domain;

/**
 * Status da meta. Só {@code ARCHIVED} é gravado; {@code ACHIEVED} é derivado ({@code current ≥ target}, S-150) e
 * {@code ACTIVE} é o resto (D1 §2.9).
 */
public enum GoalStatus {
    ACTIVE,
    ACHIEVED,
    ARCHIVED
}
