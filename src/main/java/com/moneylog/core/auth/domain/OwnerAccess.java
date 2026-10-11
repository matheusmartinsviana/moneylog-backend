package com.moneylog.core.auth.domain;

import java.time.Instant;

public record OwnerAccess(String passwordHash, boolean passwordSetupPending, String setupCodeHash, Instant setupCodeExpiresAt) {

    public boolean hasStoredPassword() {
        return passwordHash != null;
    }

    public boolean passwordSetupRequired(boolean environmentHashConfigured) {
        return passwordHash == null && (passwordSetupPending || !environmentHashConfigured);
    }

    public boolean setupCodeValidAt(Instant now) {
        return setupCodeHash != null && setupCodeExpiresAt != null && now.isBefore(setupCodeExpiresAt);
    }
}
