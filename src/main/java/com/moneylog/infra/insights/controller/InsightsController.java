package com.moneylog.infra.insights.controller;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.moneylog.core.insights.app.usecase.InsightUseCases.DailyQuery;
import com.moneylog.core.insights.app.usecase.InsightUseCases.DismissCommand;
import com.moneylog.core.insights.app.usecase.InsightUseCases.DismissInsightUseCase;
import com.moneylog.core.insights.app.usecase.InsightUseCases.GetDailyInsightsUseCase;
import com.moneylog.core.insights.app.usecase.InsightUseCases.GetInsightHistoryUseCase;
import com.moneylog.core.insights.app.usecase.InsightUseCases.GetMonthlyInsightsUseCase;
import com.moneylog.core.insights.app.usecase.InsightUseCases.HistoryQuery;
import com.moneylog.core.insights.app.usecase.InsightUseCases.MonthlyQuery;
import com.moneylog.core.insights.app.usecase.InsightUseCases.RestoreCommand;
import com.moneylog.core.insights.app.usecase.InsightUseCases.RestoreInsightUseCase;
import com.moneylog.core.insights.domain.InsightItem;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.exception.InvalidPathValueException;
import com.moneylog.infra.insights.controller.InsightDtos.DismissInput;
import com.moneylog.infra.insights.controller.InsightDtos.HistoryResponse;
import com.moneylog.infra.insights.controller.InsightDtos.InsightListResponse;
import com.moneylog.infra.insights.controller.InsightDtos.InsightResponse;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.QueryDates;
import com.moneylog.infra.web.QueryMonths;
import com.moneylog.infra.web.SliceResponse;

@RestController
public class InsightsController {

    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();
    public static final String INSIGHTS_MONTH_INVALID = "INSIGHTS_MONTH_INVALID";
    public static final String INSIGHTS_DATE_INVALID = "INSIGHTS_DATE_INVALID";
    public static final String INSIGHTS_RANGE_INVALID = "INSIGHTS_RANGE_INVALID";
    public static final String INSIGHTS_LIMIT_INVALID = "INSIGHTS_LIMIT_INVALID";
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    static final int MAX_HISTORY_MONTHS = 12;
    static final int HISTORY_LIMIT_PER_MONTH = 10;

    private final GetMonthlyInsightsUseCase monthly;
    private final GetDailyInsightsUseCase daily;
    private final GetInsightHistoryUseCase history;
    private final DismissInsightUseCase dismiss;
    private final RestoreInsightUseCase restore;
    private final TimeProvider time;

    public InsightsController(GetMonthlyInsightsUseCase monthly, GetDailyInsightsUseCase daily, GetInsightHistoryUseCase history,
                              DismissInsightUseCase dismiss, RestoreInsightUseCase restore, TimeProvider time) {
        this.monthly = monthly;
        this.daily = daily;
        this.history = history;
        this.dismiss = dismiss;
        this.restore = restore;
        this.time = time;
    }

    @GetMapping("/v1/insights")
    public ResponseEntity<SliceResponse<InsightDtos.LegacyInsightResponse>> insights(@CurrentUser UUID userId,
                                                                                    @RequestParam(required = false) String month, PageQuery page) {
        Competence competence = month == null ? null : QueryMonths.parse(month, INSIGHTS_MONTH_INVALID, "month");
        List<InsightItem> legacy = monthly.handle(new MonthlyQuery(userId, competence, false, 0, Integer.MAX_VALUE)).items().stream()
                .filter(i -> InsightDtos.LEGACY_TYPES.contains(i.insight().type())).toList();
        int from = (int) Math.min(page.offset(), legacy.size());
        int to = Math.min(legacy.size(), from + page.pageSize());
        return ok(SliceResponse.of(new Slice<>(legacy.subList(from, to), to < legacy.size()), InsightDtos.LegacyInsightResponse::of));
    }

    @GetMapping("/v1/insights/monthly")
    public ResponseEntity<InsightListResponse> monthly(@CurrentUser UUID userId, @RequestParam(required = false) String month,
                                                       @RequestParam(required = false) String limit,
                                                       @RequestParam(defaultValue = "false") boolean includeDismissed) {
        Competence competence = month == null ? null : QueryMonths.parse(month, INSIGHTS_MONTH_INVALID, "month");
        return ok(InsightListResponse.of(monthly.handle(new MonthlyQuery(userId, competence, includeDismissed, 0, limit(limit)))));
    }

    @GetMapping("/v1/insights/daily")
    public ResponseEntity<InsightListResponse> daily(@CurrentUser UUID userId,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                     @RequestParam(required = false) String limit,
                                                     @RequestParam(defaultValue = "false") boolean includeDismissed) {
        if (date != null) {
            QueryDates.inWindow(date, "date");
            if (date.isAfter(time.today())) {
                throw new InvalidPathValueException(INSIGHTS_DATE_INVALID, "date", "A data não pode ser depois de hoje.");
            }
        }
        return ok(InsightListResponse.of(daily.handle(new DailyQuery(userId, date, includeDismissed, 0, limit(limit)))));
    }

    @GetMapping("/v1/insights/history")
    public ResponseEntity<HistoryResponse> history(@CurrentUser UUID userId, @RequestParam String from, @RequestParam String to) {
        Competence start = QueryMonths.parse(from, INSIGHTS_MONTH_INVALID, "from");
        Competence end = QueryMonths.parse(to, INSIGHTS_MONTH_INVALID, "to");
        if (start.isAfter(end)) {
            throw new InvalidPathValueException(INSIGHTS_RANGE_INVALID, "from", "'from' não pode ser depois de 'to'.");
        }
        if (end.isAfter(start.plusMonths(MAX_HISTORY_MONTHS - 1))) {
            throw new InvalidPathValueException(INSIGHTS_RANGE_INVALID, "to", "O histórico cobre no máximo " + MAX_HISTORY_MONTHS + " meses.");
        }
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(HistoryResponse.of(history.handle(new HistoryQuery(userId, start, end, HISTORY_LIMIT_PER_MONTH))));
    }

    @PostMapping("/v1/insights/{id}/dismissals")
    public ResponseEntity<InsightResponse> dismiss(@CurrentUser UUID userId, @PathVariable String id, @Valid @RequestBody DismissInput body) {
        InsightResponse updated = InsightDtos.one(dismiss.handle(new DismissCommand(userId, id, body.scope())));
        return ResponseEntity.created(URI.create("/v1/insights/" + updated.id() + "/dismissals")).cacheControl(NO_STORE).body(updated);
    }

    @DeleteMapping("/v1/insights/{id}/dismissals")
    public ResponseEntity<InsightResponse> restore(@CurrentUser UUID userId, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(InsightDtos.one(restore.handle(new RestoreCommand(userId, id))));
    }

    private static int limit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            if (value >= 1 && value <= MAX_LIMIT) {
                return value;
            }
        } catch (NumberFormatException e) {
        }
        throw new InvalidPathValueException(INSIGHTS_LIMIT_INVALID, "limit", "limit deve ser um inteiro entre 1 e " + MAX_LIMIT + ".");
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(body);
    }
}
