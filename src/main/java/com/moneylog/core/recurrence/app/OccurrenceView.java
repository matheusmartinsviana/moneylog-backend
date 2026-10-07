package com.moneylog.core.recurrence.app;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.recurrence.domain.OccurrenceStatus;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.money.Money;

/**
 * Ocorrência de uma recorrência como a API mostra. {@code amount} é o do modelo (pendente e pulada) ou o da movimentação gerada
 * (realizada, que pode ter valor real diferente); {@code overdue} é "pendente com data anterior a hoje" (continua pendente, S-20).
 */
public record OccurrenceView(RecurringTransaction recurrence, LocalDate date, OccurrenceStatus status, boolean overdue, Money amount,
                             UUID transactionId) {

    public static OccurrenceView pending(RecurringTransaction recurrence, LocalDate date, LocalDate today) {
        return new OccurrenceView(recurrence, date, OccurrenceStatus.PENDING, date.isBefore(today), recurrence.amount(), null);
    }

    public static OccurrenceView skipped(RecurringTransaction recurrence, LocalDate date) {
        return new OccurrenceView(recurrence, date, OccurrenceStatus.SKIPPED, false, recurrence.amount(), null);
    }

    public static OccurrenceView realized(RecurringTransaction recurrence, LocalDate date, Money amount, UUID transactionId) {
        return new OccurrenceView(recurrence, date, OccurrenceStatus.REALIZED, false, amount, transactionId);
    }
}
