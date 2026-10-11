package com.moneylog.core.goal.domain;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import com.moneylog.core.goal.exception.GoalExceptions.GoalCheckInPeriodInvalidException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalFieldRequiredException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalTextTooLongException;
import com.moneylog.core.sk.text.PlainText;

public record GoalAction(UUID id, String title, GoalActionFrequency frequency, Instant createdAt, Set<String> doneIn) {

    public static final int TITLE_MAX_LENGTH = 100;
    public static final String ONCE = "ONCE";
    private static final Pattern WEEK = Pattern.compile("^(\\d{4})-W(0[1-9]|[1-4]\\d|5[0-3])$");

    public GoalAction {
        Objects.requireNonNull(id, "id");
        title = requireTitle(title);
        if (frequency == null) {
            throw new GoalFieldRequiredException("frequency");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        doneIn = Collections.unmodifiableSet(new TreeSet<>(doneIn == null ? Set.of() : doneIn));
    }

    public static GoalAction create(UUID id, String title, GoalActionFrequency frequency, Instant now) {
        return new GoalAction(id, title, frequency, now, Set.of());
    }

    public GoalAction edit(String newTitle, GoalActionFrequency newFrequency) {
        Set<String> kept = newFrequency == frequency ? doneIn : Set.of();
        return new GoalAction(id, newTitle, newFrequency, createdAt, kept);
    }

    public GoalAction checkIn(String period, boolean done) {
        String normalized = normalizePeriod(period);
        Set<String> next = new TreeSet<>(doneIn);
        if (done) {
            next.add(normalized);
        } else {
            next.remove(normalized);
        }
        return new GoalAction(id, title, frequency, createdAt, next);
    }

    public String normalizePeriod(String period) {
        if (frequency == GoalActionFrequency.ONE_TIME) {
            if (period == null || period.isBlank() || ONCE.equals(period.strip())) {
                return ONCE;
            }
            throw new GoalCheckInPeriodInvalidException(frequency);
        }
        if (period == null) {
            throw new GoalCheckInPeriodInvalidException(frequency);
        }
        String text = period.strip();
        if (frequency == GoalActionFrequency.MONTHLY) {
            try {
                if (text.length() == 7 && YearMonth.parse(text).toString().equals(text)) {
                    return text;
                }
            } catch (java.time.format.DateTimeParseException e) {
            }
            throw new GoalCheckInPeriodInvalidException(frequency);
        }
        if (WEEK.matcher(text).matches()) {
            return text;
        }
        throw new GoalCheckInPeriodInvalidException(frequency);
    }

    private static String requireTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new GoalFieldRequiredException("title");
        }
        String stripped = value.strip();
        PlainText.requireClean(stripped, "title", false);
        if (stripped.length() > TITLE_MAX_LENGTH) {
            throw new GoalTextTooLongException("title", TITLE_MAX_LENGTH);
        }
        return stripped;
    }
}
