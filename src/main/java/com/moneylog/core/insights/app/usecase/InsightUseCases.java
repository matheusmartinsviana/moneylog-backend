package com.moneylog.core.insights.app.usecase;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.insights.domain.Dismissal;
import com.moneylog.core.insights.domain.InsightItem;
import com.moneylog.core.insights.domain.InsufficientData;
import com.moneylog.core.sk.time.Competence;

/** Casos de uso dos insights calculados (ADR-019). Um por ação, {@code handle(...)}. */
public final class InsightUseCases {

    private InsightUseCases() {
    }

    /** Lista ordenada e paginada, mais o que não saiu por falta de dado. */
    public record InsightsView(List<InsightItem> items, boolean hasNext, List<InsufficientData> insufficient) {

        public InsightsView {
            items = List.copyOf(items);
            insufficient = List.copyOf(insufficient);
        }
    }

    public record HistoryMonth(Competence month, List<InsightItem> items, boolean hasNext, List<InsufficientData> insufficient) {

        public HistoryMonth {
            items = List.copyOf(items);
            insufficient = List.copyOf(insufficient);
        }
    }

    /** @param month {@code null} = mês corrente */
    public record MonthlyQuery(UUID userId, Competence month, boolean includeDismissed, int offset, int limit) {
    }

    /** @param date {@code null} = hoje no fuso do usuário; depois de hoje é recusada */
    public record DailyQuery(UUID userId, LocalDate date, boolean includeDismissed, int offset, int limit) {
    }

    public record HistoryQuery(UUID userId, Competence from, Competence to, int limitPerMonth) {
    }

    public record DismissCommand(UUID userId, String insightId, Dismissal.Scope scope) {
    }

    public record RestoreCommand(UUID userId, String insightId) {
    }

    public interface GetMonthlyInsightsUseCase {
        InsightsView handle(MonthlyQuery query);
    }

    public interface GetDailyInsightsUseCase {
        InsightsView handle(DailyQuery query);
    }

    public interface GetInsightHistoryUseCase {
        List<HistoryMonth> handle(HistoryQuery query);
    }

    /** Devolve o insight atualizado ({@code dismissed = true}). */
    public interface DismissInsightUseCase {
        InsightItem handle(DismissCommand command);
    }

    /** Revoga a dispensa (grava {@code revoked_at}, nunca apaga) e devolve o insight atualizado. */
    public interface RestoreInsightUseCase {
        InsightItem handle(RestoreCommand command);
    }
}
