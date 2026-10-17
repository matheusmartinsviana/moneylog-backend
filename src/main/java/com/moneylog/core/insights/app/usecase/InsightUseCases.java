package com.moneylog.core.insights.app.usecase;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.domain.ConsumptionView;
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

    public record MonthlyQuery(UUID userId, Competence month, boolean includeDismissed, int offset, int limit, ConsumptionView view) {

        public MonthlyQuery {
            view = ConsumptionView.orDefault(view);
        }

        public MonthlyQuery(UUID userId, Competence month, boolean includeDismissed, int offset, int limit) {
            this(userId, month, includeDismissed, offset, limit, ConsumptionView.MINE);
        }
    }

    public record DailyQuery(UUID userId, LocalDate date, boolean includeDismissed, int offset, int limit, ConsumptionView view) {

        public DailyQuery {
            view = ConsumptionView.orDefault(view);
        }

        public DailyQuery(UUID userId, LocalDate date, boolean includeDismissed, int offset, int limit) {
            this(userId, date, includeDismissed, offset, limit, ConsumptionView.MINE);
        }
    }

    public record HistoryQuery(UUID userId, Competence from, Competence to, int limitPerMonth, ConsumptionView view) {

        public HistoryQuery {
            view = ConsumptionView.orDefault(view);
        }

        public HistoryQuery(UUID userId, Competence from, Competence to, int limitPerMonth) {
            this(userId, from, to, limitPerMonth, ConsumptionView.MINE);
        }
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
