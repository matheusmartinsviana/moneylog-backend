package com.moneylog.infra.export.controller;

import java.time.Clock;
import java.time.Duration;

import com.moneylog.infra.web.UserRateLimiter;

public class ExportRateLimiter extends UserRateLimiter {

    public ExportRateLimiter(Clock clock, int maxRequests, Duration window) {
        super(clock, maxRequests, window);
    }
}
