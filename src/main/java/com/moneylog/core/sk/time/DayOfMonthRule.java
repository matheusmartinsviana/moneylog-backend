package com.moneylog.core.sk.time;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

import com.moneylog.core.sk.time.TimeExceptions.InvalidDayOfMonthException;

public record DayOfMonthRule(int day) {

    public DayOfMonthRule {
        if (day < 1 || day > 31) {
            throw new InvalidDayOfMonthException(day);
        }
    }

    public static DayOfMonthRule of(int day) {
        return new DayOfMonthRule(day);
    }

    public LocalDate applyTo(YearMonth month) {
        Objects.requireNonNull(month, "month");
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }

    public LocalDate applyTo(Competence competence) {
        return applyTo(competence.value());
    }
}
