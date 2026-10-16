package com.moneylog.infra.asset.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.networth.usecase.GetNetWorthOverviewUseCase;
import com.moneylog.core.finance.app.networth.usecase.GetNetWorthOverviewUseCase.GetNetWorthOverviewQuery;
import com.moneylog.core.finance.domain.NetWorthOverview;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.QueryMonths;

@RestController
@RequestMapping("/v1/net-worth/overview")
public class NetWorthOverviewController {

    public static final String MONTH_INVALID = "NET_WORTH_MONTH_INVALID";

    private final GetNetWorthOverviewUseCase overview;

    public NetWorthOverviewController(GetNetWorthOverviewUseCase overview) {
        this.overview = overview;
    }

    @GetMapping
    public NetWorthOverviewResponse get(@CurrentUser UUID userId, @RequestParam(required = false) String month,
                                        @RequestParam(required = false) Integer targetMonths) {
        if (targetMonths != null && (targetMonths < NetWorthOverview.MIN_TARGET_MONTHS || targetMonths > NetWorthOverview.MAX_TARGET_MONTHS)) {
            throw new RequestValidationException("targetMonths", "OUT_OF_RANGE", "'targetMonths' deve estar entre "
                    + NetWorthOverview.MIN_TARGET_MONTHS + " e " + NetWorthOverview.MAX_TARGET_MONTHS + ".");
        }
        return NetWorthOverviewResponse.of(overview.handle(new GetNetWorthOverviewQuery(userId,
                month == null ? null : QueryMonths.parse(month, MONTH_INVALID, "month"), targetMonths)));
    }
}
