package com.moneylog.infra.dashboard.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.dashboard.usecase.GetDashboardSummaryUseCase;
import com.moneylog.core.finance.app.dashboard.usecase.GetDashboardSummaryUseCase.GetDashboardSummaryQuery;
import com.moneylog.core.finance.app.dashboard.usecase.GetUpcomingUseCase;
import com.moneylog.core.finance.app.dashboard.usecase.GetUpcomingUseCase.GetUpcomingQuery;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.web.QueryDates;
import com.moneylog.infra.security.CurrentUser;

/** Início: resumo e compromissos futuros (D2 §4.4, T29). Tudo calculado no backend. */
@RestController
@RequestMapping("/v1/dashboard")
public class DashboardController {

    private final GetDashboardSummaryUseCase summary;
    private final GetUpcomingUseCase upcoming;
    private final TimeProvider time;

    public DashboardController(GetDashboardSummaryUseCase summary, GetUpcomingUseCase upcoming, TimeProvider time) {
        this.summary = summary;
        this.upcoming = upcoming;
        this.time = time;
    }

    @GetMapping("/summary")
    public DashboardResponse summary(@CurrentUser UUID userId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return DashboardResponse.of(summary.handle(new GetDashboardSummaryQuery(userId, QueryDates.inWindow(asOf, "asOf"))));
    }

    @GetMapping("/upcoming")
    public UpcomingResponse upcoming(@CurrentUser UUID userId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        QueryDates.inWindow(from, "from");
        QueryDates.inWindow(to, "to");
        // "from" padrão = hoje; "to" antes de hoje (ou antes de "from") é faixa invertida, 400 e não 500; a janela é de até 3 anos
        LocalDate effectiveFrom = from == null ? time.today() : from;
        QueryDates.range(effectiveFrom, to == null ? effectiveFrom : to);
        return UpcomingResponse.of(upcoming.handle(new GetUpcomingQuery(userId, from, to)));
    }
}
