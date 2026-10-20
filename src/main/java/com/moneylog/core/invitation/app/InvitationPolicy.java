package com.moneylog.core.invitation.app;

import java.time.Duration;
import java.time.Instant;

public record InvitationPolicy(boolean enabled, int validityDays, int maxUsers) {

    public InvitationPolicy {
        if (validityDays < 1) {
            throw new IllegalArgumentException("validityDays deve ser positivo");
        }
        if (maxUsers < 1) {
            throw new IllegalArgumentException("maxUsers deve ser positivo");
        }
    }

    public Duration validity() {
        return Duration.ofDays(validityDays);
    }

    public Instant expiresAt(Instant now) {
        return now.plus(validity());
    }
}
