package com.moneylog.core.notification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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

    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

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
