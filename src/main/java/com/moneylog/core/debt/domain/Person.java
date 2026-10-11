package com.moneylog.core.debt.domain;

import java.util.UUID;

import com.moneylog.core.debt.exception.DebtExceptions.DebtFieldRequiredException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonArchiveStateException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonArchivedException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNameRequiredException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;

public final class Person {

    public static final int NAME_MAX_LENGTH = 100;

    private final UUID id;
    private final UUID userId;
    private String name;
    private String notes;
    private boolean archived;

    private Person(UUID id, UUID userId, String name, String notes, boolean archived) {
        if (id == null) {
            throw new DebtFieldRequiredException("personId");
        }
        if (userId == null) {
            throw new DebtFieldRequiredException("userId");
        }
        this.id = id;
        this.userId = userId;
        this.name = requireName(name);
        this.notes = notes == null || notes.isBlank() ? null : notes.strip();
        this.archived = archived;
    }

    public static Person create(UUID id, UUID userId, String name, String notes) {
        return new Person(id, userId, name, notes, false);
    }

    public static Person restore(UUID id, UUID userId, String name, String notes, boolean archived) {
        return new Person(id, userId, name, notes, archived);
    }

    public void rename(String newName) {
        this.name = requireName(newName);
    }

    public void changeNotes(String newNotes) {
        this.notes = newNotes == null || newNotes.isBlank() ? null : newNotes.strip();
    }

    public void archive() {
        if (archived) {
            throw new PersonArchiveStateException(true);
        }
        archived = true;
    }

    public void unarchive() {
        if (!archived) {
            throw new PersonArchiveStateException(false);
        }
        archived = false;
    }

    public void ensureBelongsTo(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new PersonNotFoundException(id);
        }
    }

    public void ensureCanReceiveDebts() {
        if (archived) {
            throw new PersonArchivedException();
        }
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public String name() {
        return name;
    }

    public String notes() {
        return notes;
    }

    public boolean isArchived() {
        return archived;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > NAME_MAX_LENGTH) {
            throw new PersonNameRequiredException();
        }
        return name.strip();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Person other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
