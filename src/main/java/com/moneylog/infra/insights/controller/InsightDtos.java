package com.moneylog.infra.insights.controller;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

import com.moneylog.core.insights.app.usecase.InsightUseCases.HistoryMonth;
import com.moneylog.core.insights.app.usecase.InsightUseCases.InsightsView;
import com.moneylog.core.insights.domain.Dismissal;
import com.moneylog.core.insights.domain.Insight;
import com.moneylog.core.insights.domain.InsightItem;
import com.moneylog.core.insights.domain.InsufficientData;

/** Entrada e saída de {@code /v1/insights/*} (ADR-019). O backend devolve {@code key} e {@code params}; o front traduz e formata. */
public final class InsightDtos {

    private InsightDtos() {
    }

    public record PeriodDto(String kind, String value) {
    }

    public record ConfidenceDto(int n, int window, String z) {
    }

    public record InsightResponse(String id, String type, String key, Map<String, String> params, List<String> factRefs, String severity,
                                  int priority, PeriodDto period, String source, String basis, ConfidenceDto confidence, String subjectId,
                                  boolean dismissed) {
        static InsightResponse of(InsightItem item) {
            Insight i = item.insight();
            return new InsightResponse(item.id(), i.type(), i.key(), i.params(), i.factRefs(), i.severity().name(), i.priority(),
                    i.period() == null ? null : new PeriodDto(i.period().kind().name(), i.period().value()), "COMPUTED",
                    i.basis() == null ? null : i.basis().name(),
                    i.confidence() == null ? null : new ConfidenceDto(i.confidence().n(), i.confidence().window(), i.confidence().z()),
                    i.subjectId(), item.dismissed());
        }
    }

    /** O formato original (2.8.0) do alias {@code GET /v1/insights}: só os quatro campos de sempre mais {@code source}. */
    public record LegacyInsightResponse(String type, String key, Map<String, String> params, List<String> factRefs, String source) {
        static LegacyInsightResponse of(InsightItem item) {
            Insight i = item.insight();
            return new LegacyInsightResponse(i.type(), i.key(), i.params(), i.factRefs(), "COMPUTED");
        }
    }

    public record InsufficientResponse(String type, String reason, Integer missing) {
        static InsufficientResponse of(InsufficientData d) {
            return new InsufficientResponse(d.type(), d.reason().name(), d.missing());
        }
    }

    /** Página de insights + o que não saiu por falta de dado (mesmo envelope {@code items/hasNext} das listas). */
    public record InsightListResponse(List<InsightResponse> items, boolean hasNext, List<InsufficientResponse> insufficient) {
        static InsightListResponse of(InsightsView view) {
            return new InsightListResponse(view.items().stream().map(InsightResponse::of).toList(), view.hasNext(),
                    view.insufficient().stream().map(InsufficientResponse::of).toList());
        }
    }

    public record HistoryMonthResponse(String month, List<InsightResponse> items, boolean hasNext, List<InsufficientResponse> insufficient) {
        static HistoryMonthResponse of(HistoryMonth m) {
            return new HistoryMonthResponse(m.month().toString(), m.items().stream().map(InsightResponse::of).toList(), m.hasNext(),
                    m.insufficient().stream().map(InsufficientResponse::of).toList());
        }
    }

    public record HistoryResponse(List<HistoryMonthResponse> items) {
        static HistoryResponse of(List<HistoryMonth> months) {
            return new HistoryResponse(months.stream().map(HistoryMonthResponse::of).toList());
        }
    }

    public record DismissInput(@NotNull Dismissal.Scope scope) {
    }

    /** Os tipos que o contrato original conhecia: o alias não devolve nenhum outro (cliente antigo não sabe o que fazer com ele). */
    static final java.util.Set<String> LEGACY_TYPES = java.util.Set.of("CATEGORY_DELTA", "CARD_COMMITTED");

    static InsightResponse one(InsightItem item) {
        return InsightResponse.of(item);
    }
}
