package com.moneylog.core.transaction.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.sk.text.PlainText;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionFieldRequiredException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionTextTooLongException;

public record TransactionHeader(UUID id, UUID userId, LocalDate date, String description, String notes,
                                TransactionOrigin origin) {

    public static final int DESCRIPTION_MAX_LENGTH = 200;
    public static final int NOTES_MAX_LENGTH = 2000;

    public TransactionHeader {
        required(id, "id");
        required(userId, "userId");
        required(date, "date");
        FinancialDates.requireInWindow(date, "date");
        required(origin, "origin");
        description = optionalText(description, "description", DESCRIPTION_MAX_LENGTH);
        notes = optionalText(notes, "notes", NOTES_MAX_LENGTH);
    }

    public static TransactionHeader of(UUID id, UUID userId, LocalDate date, String description) {
        return new TransactionHeader(id, userId, date, description, null, TransactionOrigin.manual());
    }

    public TransactionHeader withNotes(String newNotes) {
        return new TransactionHeader(id, userId, date, description, newNotes, origin);
    }

    public TransactionHeader withOrigin(TransactionOrigin newOrigin) {
        return new TransactionHeader(id, userId, date, description, notes, newOrigin);
    }

    public static String optionalText(String value, String field, int max) {
        PlainText.requireClean(value, field, "notes".equals(field));
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > max) {
            throw new TransactionTextTooLongException(field, max);
        }
        return trimmed;
    }

    static <T> T required(T value, String field) {
        if (value == null) {
            throw new TransactionFieldRequiredException(field);
        }
        return value;
    }
}
