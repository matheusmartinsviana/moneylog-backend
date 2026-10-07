package com.moneylog.core.notification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Notificação da central. Só muda lida/arquivada; nunca é apagada em silêncio (S-179). O push só é enviado se {@code pushEligible} (fora da janela de
 * silêncio e com push ligado na criação) e ainda não foi enviado.
 */
public final class Notification {

    private final UUID id;
    private final UUID userId;
    private final NotificationDraft draft;
    private final Instant createdAt;
    private final boolean pushEligible;
    private Instant readAt;
    private boolean archived;
    private Instant pushSentAt;

    private Notification(UUID id, UUID userId, NotificationDraft draft, Instant createdAt, boolean pushEligible, Instant readAt, boolean archived,
                         Instant pushSentAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.draft = Objects.requireNonNull(draft, "draft");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.pushEligible = pushEligible;
        this.readAt = readAt;
        this.archived = archived;
        this.pushSentAt = pushSentAt;
    }

    public static Notification create(UUID id, UUID userId, NotificationDraft draft, Instant now, boolean pushEligible) {
        return new Notification(id, userId, draft, now, pushEligible, null, false, null);
    }

    public static Notification restore(UUID id, UUID userId, NotificationDraft draft, Instant createdAt, boolean pushEligible, Instant readAt,
                                       boolean archived, Instant pushSentAt) {
        return new Notification(id, userId, draft, createdAt, pushEligible, readAt, archived, pushSentAt);
    }

    /** Idempotente: marcar de novo uma lida mantém o instante original. */
    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    /** Lida há mais de {@code days} dias sai da central (arquivada), sem apagar (S-179). */
    public boolean shouldArchive(Instant now, int days) {
        return !archived && readAt != null && readAt.isBefore(now.minusSeconds(days * 86_400L));
    }

    public void archive() {
        archived = true;
    }

    public void pushSent(Instant now) {
        pushSentAt = now;
    }

    public boolean isUnread() {
        return readAt == null;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public NotificationDraft draft() {
        return draft;
    }

    public NotificationKind kind() {
        return draft.kind();
    }

    public String titleKey() {
        return draft.titleKey();
    }

    public Map<String, String> params() {
        return draft.params();
    }

    public LocalDate onDate() {
        return draft.onDate();
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean pushEligible() {
        return pushEligible;
    }

    public Instant readAt() {
        return readAt;
    }

    public boolean archived() {
        return archived;
    }

    public Instant pushSentAt() {
        return pushSentAt;
    }
}
