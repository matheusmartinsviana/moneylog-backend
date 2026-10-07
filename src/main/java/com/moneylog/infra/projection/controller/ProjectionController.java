package com.moneylog.infra.projection.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.projection.GetProjectionUseCase;
import com.moneylog.core.finance.app.projection.GetProjectionUseCase.GetProjectionQuery;
import com.moneylog.infra.security.CurrentUser;

/** Projeções determinísticas (PR 00 §19, S-152, S-155): sempre rotuladas como projeção. Tudo calculado no backend. */
@RestController
@RequestMapping("/v1/projections")
public class ProjectionController {

    private final GetProjectionUseCase projection;

    public ProjectionController(GetProjectionUseCase projection) {
        this.projection = projection;
    }

    @GetMapping
    public ProjectionResponse get(@CurrentUser UUID userId, @RequestParam(defaultValue = "M6") ProjectionHorizon horizon,
                                  @RequestParam(defaultValue = "false") boolean includeTrend) {
        return ProjectionResponse.of(projection.handle(new GetProjectionQuery(userId, horizon.horizon(), includeTrend)), horizon);
    }
}
