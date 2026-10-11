package com.moneylog.core.goal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.goal.exception.GoalExceptions.GoalFieldRequiredException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalReservationAmountZeroException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalTextTooLongException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.text.PlainText;
import com.moneylog.core.sk.time.FinancialDates;

public record GoalReservation(UUID id, UUID goalId, UUID accountId, Money amount, LocalDate date, String note, Instant createdAt) {

    public static final int NOTE_MAX_LENGTH = 200;

    public GoalReservation {
        Objects.requireNonNull(id, "id");
        if (goalId == null) {
            throw new GoalFieldRequiredException("goalId");
        }
        if (accountId == null) {
            throw new GoalFieldRequiredException("accountId");
        }
        if (amount == null) {
            throw new GoalFieldRequiredException("amount");
        }
        if (date == null) {
            throw new GoalFieldRequiredException("date");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        if (amount.isZero()) {
            throw new GoalReservationAmountZeroException();
        }
        if (!CurrencyCode.BRL.equals(amount.currency())) {
            throw new com.moneylog.core.goal.exception.GoalExceptions.GoalReservationAccountNotEligibleException("só BRL no MVP.");
        }
        AmountLimit.ensureWithinLimit(amount, "amount");
        FinancialDates.requireInWindow(date, "date");
        note = note == null || note.isBlank() ? null : note.strip();
        if (note != null) {
            PlainText.requireClean(note, "note", true);
            if (note.length() > NOTE_MAX_LENGTH) {
                throw new GoalTextTooLongException("note", NOTE_MAX_LENGTH);
            }
        }
    }

    public boolean isRelease() {
        return amount.isNegative();
    }
}
