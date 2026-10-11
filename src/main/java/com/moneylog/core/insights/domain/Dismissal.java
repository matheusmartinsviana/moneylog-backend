package com.moneylog.core.insights.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * Dispensa de um insight, <b>só inserção</b> (auditoria): revogar grava {@code revokedAt}, nunca apaga a linha.
 *
 * <p>Escopos: {@code TODAY} some só no dia da dispensa (mesmo insight); {@code MONTH} some o mesmo tipo e assunto até o fim do mês da dispensa;
 * {@code TYPE} ("parar de avisar deste tipo") some todo insight do tipo até reverter.
 *
 * @param fingerprint insight dispensado (período incluído)
 * @param subjectId   assunto dentro do tipo ({@code null} = único)
 * @param dismissedOn dia (no fuso do usuário) em que foi dispensado
 */
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

    /** Esta dispensa (ainda ativa) esconde o insight em {@code today}? */
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
