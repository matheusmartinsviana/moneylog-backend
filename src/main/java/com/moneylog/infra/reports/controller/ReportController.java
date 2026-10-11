package com.moneylog.infra.reports.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.moneylog.core.finance.app.reports.usecase.GetIncomeExpenseSeriesUseCase;
import com.moneylog.core.finance.app.reports.usecase.GetIncomeExpenseSeriesUseCase.GetIncomeExpenseSeriesQuery;
import com.moneylog.core.finance.app.reports.usecase.GetSpendingByCategoryUseCase;
import com.moneylog.core.finance.app.reports.usecase.GetSpendingByCategoryUseCase.GetSpendingByCategoryQuery;
import com.moneylog.core.finance.domain.ReportPeriod;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.infra.exception.InvalidPathValueException;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.QueryMonths;

@RestController
@RequestMapping("/v1/reports")
@Validated
public class ReportController {

    public static final String REPORT_PERIOD_INVALID = "REPORT_PERIOD_INVALID";
    public static final String REPORT_MONTH_INVALID = "REPORT_MONTH_INVALID";
    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final GetIncomeExpenseSeriesUseCase series;
    private final GetSpendingByCategoryUseCase spending;

    public ReportController(GetIncomeExpenseSeriesUseCase series, GetSpendingByCategoryUseCase spending) {
        this.series = series;
        this.spending = spending;
    }

    @GetMapping("/income-expenses")
    public ResponseEntity<IncomeExpenseSeriesResponse> incomeExpenses(@CurrentUser UUID userId,
                                                                      @RequestParam(required = false) String period) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(IncomeExpenseSeriesResponse.of(series.handle(new GetIncomeExpenseSeriesQuery(userId, parsePeriod(period)))));
    }

    @GetMapping("/spending-by-category")
    public ResponseEntity<SpendingByCategoryResponse> spendingByCategory(@CurrentUser UUID userId,
                                                                         @RequestParam(required = false) String month,
                                                                         @RequestParam(defaultValue = "5") @Min(1) @Max(10) int limit) {
        Competence parsed = month == null ? null : QueryMonths.parse(month, REPORT_MONTH_INVALID, "month");
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(SpendingByCategoryResponse.of(spending.handle(new GetSpendingByCategoryQuery(userId, parsed, limit))));
    }

    static ReportPeriod parsePeriod(String period) {
        try {
            if (period != null) {
                return ReportPeriod.valueOf(period);
            }
        } catch (IllegalArgumentException e) {
        }
        throw new InvalidPathValueException(REPORT_PERIOD_INVALID, "period", "Período inválido: use D7, D30, M3 ou M12.");
    }
}
