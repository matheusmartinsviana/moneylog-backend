package com.moneylog.infra.summary.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.summary.GetMonthlySummaryUseCase;
import com.moneylog.core.finance.app.summary.GetMonthlySummaryUseCase.GetMonthlySummaryQuery;
import com.moneylog.core.finance.domain.ConsumptionView;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.QueryMonths;
import com.moneylog.infra.web.QueryViews;

@RestController
@RequestMapping("/v1/summaries")
public class SummaryController {

    public static final String SUMMARY_MONTH_INVALID = "SUMMARY_MONTH_INVALID";

    private final GetMonthlySummaryUseCase summary;

    public SummaryController(GetMonthlySummaryUseCase summary) {
        this.summary = summary;
    }

    @GetMapping("/{month}")
    public MonthlySummaryResponse get(@CurrentUser UUID userId, @PathVariable String month,
                                      @RequestParam(required = false) String view) {
        ConsumptionView parsedView = QueryViews.parse(view);
        return MonthlySummaryResponse.of(summary.handle(new GetMonthlySummaryQuery(userId, parse(month), parsedView)), parsedView);
    }

    static Competence parse(String month) {
        return QueryMonths.parse(month, SUMMARY_MONTH_INVALID, "month");
    }
}
