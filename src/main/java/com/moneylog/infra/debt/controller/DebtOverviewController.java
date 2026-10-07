package com.moneylog.infra.debt.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.debts.usecase.GetDebtOverviewUseCase;
import com.moneylog.core.finance.app.debts.usecase.GetDebtOverviewUseCase.GetDebtOverviewQuery;
import com.moneylog.core.finance.domain.DebtOverviewCalculator;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.security.CurrentUser;

/** Visão total de dívidas (ADR-013): tudo calculado no backend, só do usuário do token. */
@RestController
@RequestMapping("/v1/debts/overview")
public class DebtOverviewController {

    private final GetDebtOverviewUseCase overview;

    public DebtOverviewController(GetDebtOverviewUseCase overview) {
        this.overview = overview;
    }

    @GetMapping
    public DebtOverviewResponse get(@CurrentUser UUID userId, @RequestParam(required = false) Integer months) {
        int horizon = months == null ? DebtOverviewCalculator.DEFAULT_MONTHS : months;
        if (horizon < DebtOverviewCalculator.MIN_MONTHS || horizon > DebtOverviewCalculator.MAX_MONTHS) {
            throw new RequestValidationException("months", "OUT_OF_RANGE",
                    "'months' deve estar entre " + DebtOverviewCalculator.MIN_MONTHS + " e " + DebtOverviewCalculator.MAX_MONTHS + ".");
        }
        return DebtOverviewResponse.of(overview.handle(new GetDebtOverviewQuery(userId, horizon)));
    }
}
