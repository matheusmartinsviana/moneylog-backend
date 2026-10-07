package com.moneylog.core.recurrence.app.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.recurrence.app.OccurrenceListing;
import com.moneylog.core.recurrence.domain.OccurrenceStatus;
import com.moneylog.core.sk.page.PageQuery;

public interface ListOccurrencesUseCase {

    /**
     * Ocorrências em ordem {@code date asc, recurrenceId asc}, no intervalo {@code [from, to]} (as três situações, ou só a pedida em
     * {@code status}). <b>Sem {@code from}</b>, a janela começa no início da agenda: é o jeito de listar as <b>vencidas e não
     * realizadas</b>, que continuam pendentes até serem realizadas ou puladas (S-20, {@code overdue = true}). {@code to} padrão =
     * ({@code from}, ou hoje se também falta) + 30 dias.
     * As pendentes <b>vencidas</b> (data anterior a hoje) vêm limitadas às {@value #MAX_OVERDUE_OCCURRENCES} mais recentes por chamada,
     * somando todas as recorrências; havendo mais, {@code overdueTruncated = true} (S-240). As demais situações não são limitadas.
     */
    OccurrenceListing handle(ListOccurrencesQuery query);

    int DEFAULT_WINDOW_DAYS = 30;

    /** Teto de ocorrências pendentes vencidas devolvidas por chamada (S-240). */
    int MAX_OVERDUE_OCCURRENCES = 500;

    record ListOccurrencesQuery(UUID userId, LocalDate from, LocalDate to, OccurrenceStatus status, PageQuery page) {
    }
}
