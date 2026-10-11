package com.moneylog.core.notification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.notification.exception.NotificationExceptions.InvalidReminderException;
import com.moneylog.core.notification.exception.NotificationExceptions.ReminderAlreadyDoneException;
import com.moneylog.core.sk.text.PlainText;
import com.moneylog.core.sk.time.FinancialDates;

public final class Reminder {

    public static final int TITLE_MAX = 120;
    public static final int NOTE_MAX = 500;

    private final UUID id;
    private final UUID userId;
    private final Instant createdAt;
    private String title;
    private LocalDate remindOn;
    private LocalTime remindAt;
    private String note;
    private String linkType;
    private UUID linkId;
    private Instant doneAt;

    private Reminder(UUID id, UUID userId, String title, LocalDate remindOn, LocalTime remindAt, String note, String linkType, UUID linkId,
                     Instant createdAt, Instant doneAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.doneAt = doneAt;
        apply(title, remindOn, remindAt, note, linkType, linkId);
    }

    public static Reminder create(UUID id, UUID userId, String title, LocalDate remindOn, LocalTime remindAt, String note, String linkType,
                                  UUID linkId, Instant now) {
        return new Reminder(id, userId, title, remindOn, remindAt, note, linkType, linkId, now, null);
    }

    public static Reminder restore(UUID id, UUID userId, String title, LocalDate remindOn, LocalTime remindAt, String note, String linkType,
                                   UUID linkId, Instant createdAt, Instant doneAt) {
        return new Reminder(id, userId, title, remindOn, remindAt, note, linkType, linkId, createdAt, doneAt);
    }

    public void update(String newTitle, LocalDate newRemindOn, LocalTime newRemindAt, String newNote, String newLinkType, UUID newLinkId) {
        ensureOpen();
        apply(newTitle, newRemindOn, newRemindAt, newNote, newLinkType, newLinkId);
    }

    public void done(Instant now) {
        ensureOpen();
        doneAt = now;
    }

    public void snooze(LocalDate newDate) {
        ensureOpen();
        FinancialDates.requireInWindow(newDate, "remindOn");
        remindOn = newDate;
    }

    private void ensureOpen() {
        if (doneAt != null) {
            throw new ReminderAlreadyDoneException(id);
        }
    }

    private void apply(String newTitle, LocalDate newRemindOn, LocalTime newRemindAt, String newNote, String newLinkType, UUID newLinkId) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new InvalidReminderException("o título é obrigatório.");
        }
        String trimmed = newTitle.strip();
        if (trimmed.length() > TITLE_MAX || !PlainText.isSafeSingleLine(trimmed)) {
            throw new InvalidReminderException("o título deve ter até " + TITLE_MAX + " caracteres, sem controles.");
        }
        if (newRemindOn == null) {
            throw new InvalidReminderException("a data é obrigatória.");
        }
        FinancialDates.requireInWindow(newRemindOn, "remindOn");
        if (newNote != null && (newNote.length() > NOTE_MAX || PlainText.hasControlCharacters(newNote, true))) {
            throw new InvalidReminderException("a nota deve ter até " + NOTE_MAX + " caracteres, sem controles.");
        }
        if ((newLinkType == null) != (newLinkId == null)) {
            throw new InvalidReminderException("o vínculo precisa de tipo e id juntos.");
        }
        if (newLinkType != null && !newLinkType.matches("[A-Z_]{1,20}")) {
            throw new InvalidReminderException("tipo de vínculo inválido.");
        }
        this.title = trimmed;
        this.remindOn = newRemindOn;
        this.remindAt = newRemindAt;
        this.note = newNote == null || newNote.isBlank() ? null : newNote.strip();
        this.linkType = newLinkType;
        this.linkId = newLinkId;
    }

    public boolean isDone() {
        return doneAt != null;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public String title() {
        return title;
    }

    public LocalDate remindOn() {
        return remindOn;
    }

    public LocalTime remindAt() {
        return remindAt;
    }

    public String note() {
        return note;
    }

    public String linkType() {
        return linkType;
    }

    public UUID linkId() {
        return linkId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant doneAt() {
        return doneAt;
    }
}
