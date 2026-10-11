package com.moneylog.infra.search.controller;

import java.time.Clock;
import java.time.Duration;

import com.moneylog.infra.web.UserRateLimiter;

public class SearchRateLimiter extends UserRateLimiter {

    public SearchRateLimiter(Clock clock, int maxRequests, Duration window) {
        super(clock, maxRequests, window);
    }
}
