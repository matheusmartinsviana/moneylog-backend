package com.moneylog.core.notification.domain;

/** Tipos de notificação (S-174). */
public enum NotificationKind {
    INVOICE_DUE,
    DEBT_COLLECT,
    DEBT_PAYABLE_DUE,
    RECURRENCE_PENDING,
    COMMITTED_NEXT_MONTH,
    DAILY_SUMMARY,
    REMINDER,
    GOAL_DEADLINE,
    /** "N lembretes": as que passaram do limite diário (S-175). Não é configurável por tipo. */
    GROUPED;

    /** Tipos que o usuário liga e desliga em Configurações. */
    public boolean configurable() {
        return this != GROUPED && this != DAILY_SUMMARY && this != REMINDER;
    }
}
