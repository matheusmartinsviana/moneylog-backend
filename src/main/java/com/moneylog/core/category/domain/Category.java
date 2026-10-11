package com.moneylog.core.category.domain;

import java.util.UUID;

import com.moneylog.core.category.exception.CategoryExceptions.CategoryAlreadyArchivedException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryArchivedException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryFieldRequiredException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryKindMismatchException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNameRequiredException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNameTooLongException;
import com.moneylog.core.sk.text.PlainText;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotArchivedException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryOwnerRequiredException;
import com.moneylog.core.sk.ledger.BookRef;

public final class Category {

    public static final int NAME_MAX_LENGTH = 60;

    private final UUID id;
    private final UUID userId;
    private String name;
    private final CategoryKind kind;
    private final boolean system;
    private boolean archived;
    private final long version;

    private Category(UUID id, UUID userId, String name, CategoryKind kind, boolean system, boolean archived, long version) {
        this.id = required(id, "id");
        if (userId == null) {
            throw new CategoryOwnerRequiredException();
        }
        this.userId = userId;
        this.name = requireName(name);
        this.kind = required(kind, "kind");
        this.system = system;
        this.archived = archived;
        this.version = version;
    }

    public static Category create(UUID id, UUID userId, String name, CategoryKind kind) {
        return new Category(id, userId, name, kind, false, false, 0L);
    }

    public static Category createSystem(UUID id, UUID userId, String name, CategoryKind kind) {
        return new Category(id, userId, name, kind, true, false, 0L);
    }

    public static Category restore(UUID id, UUID userId, String name, CategoryKind kind, boolean system,
                                   boolean archived, long version) {
        return new Category(id, userId, name, kind, system, archived, version);
    }

    public void rename(String newName) {
        this.name = requireName(newName);
    }

    public void archive() {
        if (archived) {
            throw new CategoryAlreadyArchivedException();
        }
        this.archived = true;
    }

    public void unarchive() {
        if (!archived) {
            throw new CategoryNotArchivedException();
        }
        this.archived = false;
    }

    public void ensureKind(CategoryKind expected) {
        if (kind != expected) {
            throw new CategoryKindMismatchException(expected, kind);
        }
    }

    public void ensureCanReceiveTransactions() {
        if (archived) {
            throw new CategoryArchivedException();
        }
    }

    public void ensureBelongsTo(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new CategoryNotFoundException(id);
        }
    }

    public BookRef book() {
        return kind == CategoryKind.EXPENSE ? BookRef.expense(id) : BookRef.income(id);
    }

    public Category forExistingUse() {
        return archived ? new Category(id, userId, name, kind, system, false, version) : this;
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

    public CategoryKind kind() {
        return kind;
    }

    public boolean isSystem() {
        return system;
    }

    public boolean isArchived() {
        return archived;
    }

    public long version() {
        return version;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new CategoryNameRequiredException();
        }
        PlainText.requireClean(name, "name", false);
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new CategoryNameTooLongException(NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new CategoryFieldRequiredException(field);
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Category other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Category[" + id + ", " + name + ", " + kind + (archived ? ", ARCHIVED" : "") + "]";
    }
}
