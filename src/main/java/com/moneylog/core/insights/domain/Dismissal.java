package com.moneylog.core.insights.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

public record Dismissal(UUID id, UUID userId, String fingerprint, String type, String subjectId, Scope scope, LocalDate dismissedOn,
                        Instant createdAt, Instant revokedAt) {

    public Dismissal {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(dismissedOn, "dismissedOn");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public enum Scope {
        TODAY,
        MONTH,
        TYPE
    }

    public static Dismissal of(UUID id, UUID userId, Insight insight, String fingerprint, Scope scope, LocalDate today, Instant now) {
        return new Dismissal(id, userId, fingerprint, insight.type(), insight.subjectId(), scope, today, now, null);
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public boolean hides(String insightFingerprint, Insight insight, LocalDate today) {
        if (!isActive()) {
            return false;
        }
        return switch (scope) {
            case TODAY -> fingerprint.equals(insightFingerprint) && dismissedOn.equals(today);
            case MONTH -> type.equals(insight.type()) && Objects.equals(subjectId, insight.subjectId())
                    && YearMonth.from(dismissedOn).equals(YearMonth.from(today));
            case TYPE -> type.equals(insight.type());
        };
    }
}
