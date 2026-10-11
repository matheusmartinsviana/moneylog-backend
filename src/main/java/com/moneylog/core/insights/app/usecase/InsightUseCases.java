package com.moneylog.core.insights.app.usecase;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.insights.domain.Dismissal;
import com.moneylog.core.insights.domain.InsightItem;
import com.moneylog.core.insights.domain.InsufficientData;
import com.moneylog.core.sk.time.Competence;

public final class InsightUseCases {

    private InsightUseCases() {
    }

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

    public record MonthlyQuery(UUID userId, Competence month, boolean includeDismissed, int offset, int limit) {
    }

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

    public interface DismissInsightUseCase {
        InsightItem handle(DismissCommand command);
    }

    public interface RestoreInsightUseCase {
        InsightItem handle(RestoreCommand command);
    }
}
