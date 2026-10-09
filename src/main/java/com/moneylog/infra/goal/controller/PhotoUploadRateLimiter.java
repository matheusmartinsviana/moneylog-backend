package com.moneylog.infra.goal.controller;

import java.time.Clock;
import java.time.Duration;

import com.moneylog.infra.web.UserRateLimiter;

/** Envio de fotos: decodificar imagem é caro na instância pequena (ADR-018); limite por usuário, em memória, como a exportação. */
public class PhotoUploadRateLimiter extends UserRateLimiter {

    public PhotoUploadRateLimiter(Clock clock, int maxRequests, Duration window) {
        super(clock, maxRequests, window);
    }
}
