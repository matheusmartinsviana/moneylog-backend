package com.moneylog.core.notification.domain;

/** Frequência do resumo diário (S-175). */
public enum SummaryFrequency {
    /** Todo dia no horário. */
    DAILY,
    /** Só se houver compromisso nos próximos 7 dias (ou vencido). */
    ONLY_DUE,
    /** Nunca. */
    OFF
}
