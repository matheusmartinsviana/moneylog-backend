package com.moneylog.infra.goal.controller;

import java.time.Clock;
import java.time.Duration;

import com.moneylog.infra.web.UserRateLimiter;

public class PhotoUploadRateLimiter extends UserRateLimiter {

    public PhotoUploadRateLimiter(Clock clock, int maxRequests, Duration window) {
        super(clock, maxRequests, window);
    }
}
