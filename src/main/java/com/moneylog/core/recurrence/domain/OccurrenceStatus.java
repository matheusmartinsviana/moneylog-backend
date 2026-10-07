package com.moneylog.core.recurrence.domain;

/** Situação de uma ocorrência (D1 §2.7): prevista e ainda não decidida, realizada (virou movimentação) ou pulada. */
public enum OccurrenceStatus {
    PENDING,
    REALIZED,
    SKIPPED
}
