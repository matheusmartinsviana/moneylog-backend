package com.moneylog.infra.auth.controller;

import java.time.Instant;

public record AuthTokenResponse(String accessToken, Instant expiresAt) {
}
