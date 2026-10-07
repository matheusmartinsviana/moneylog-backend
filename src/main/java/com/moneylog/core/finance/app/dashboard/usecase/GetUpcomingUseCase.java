package com.moneylog.core.finance.app.dashboard.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.dashboard.UpcomingView;

public interface GetUpcomingUseCase {

    /**
     * Compromissos de {@code from} a {@code to} (padrão S-64: de hoje a hoje + 30 dias); os vencidos entram sempre.
     *
     * @throws IllegalArgumentException {@code from} depois de {@code to} (a fronteira web responde 400 antes)
     */
    UpcomingView handle(GetUpcomingQuery query);

    /** Datas {@code null} usam o padrão: {@code from} = hoje; {@code to} = {@code from} + 30 dias. */
    record GetUpcomingQuery(UUID userId, LocalDate from, LocalDate to) {
    }

    /** Janela padrão (S-64). */
    int DEFAULT_WINDOW_DAYS = 30;

    /**
     * Teto de ocorrências de recorrência <b>vencidas e pendentes</b> devolvidas por chamada (S-240): as mais recentes; havendo mais,
     * {@code overdueTruncated = true}. Os totais e o comprometido do dashboard continuam completos.
     */
    int MAX_OVERDUE_OCCURRENCES = 500;
}
