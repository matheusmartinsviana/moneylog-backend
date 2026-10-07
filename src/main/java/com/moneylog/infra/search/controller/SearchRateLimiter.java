package com.moneylog.infra.search.controller;

import java.time.Clock;
import java.time.Duration;

import com.moneylog.infra.web.UserRateLimiter;

/** 120 pedidos por minuto por usuário (S-287): a busca roda a cada pausa de digitação, então o limite de 6 da exportação a quebraria. */
public class SearchRateLimiter extends UserRateLimiter {

    public SearchRateLimiter(Clock clock, int maxRequests, Duration window) {
        super(clock, maxRequests, window);
    }
}
