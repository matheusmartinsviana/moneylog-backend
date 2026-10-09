package com.moneylog.core.sk.time;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import com.moneylog.core.sk.time.TimeExceptions.InvalidCompetenceException;

/**
 * Mês de competência ({@code YearMonth}), usado na lente de consumo/receita (D1 §1.2).
 * A competência de um posting é o mês da data efetiva dele, exceto na receita com competência declarada (ADR-017). Formato textual: {@code AAAA-MM}.
 */
public record Competence(YearMonth value) implements Comparable<Competence> {

    public Competence {
        Objects.requireNonNull(value, "value");
    }

    public static Competence of(int year, int month) {
        try {
            return new Competence(YearMonth.of(year, month));
        } catch (DateTimeException e) {
            throw new InvalidCompetenceException(year + "-" + month);
        }
    }

    public static Competence from(LocalDate date) {
        return new Competence(YearMonth.from(Objects.requireNonNull(date, "date")));
    }

    public static Competence parse(String text) {
        try {
            return new Competence(YearMonth.parse(Objects.requireNonNull(text, "text")));
        } catch (DateTimeParseException e) {
            throw new InvalidCompetenceException(text);
        }
    }

    public Competence plusMonths(long months) {
        return new Competence(value.plusMonths(months));
    }

    public Competence minusMonths(long months) {
        return new Competence(value.minusMonths(months));
    }

    public LocalDate firstDay() {
        return value.atDay(1);
    }

    public LocalDate lastDay() {
        return value.atEndOfMonth();
    }

    public boolean contains(LocalDate date) {
        return YearMonth.from(date).equals(value);
    }

    public boolean isBefore(Competence other) {
        return value.isBefore(other.value);
    }

    public boolean isAfter(Competence other) {
        return value.isAfter(other.value);
    }

    @Override
    public int compareTo(Competence other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
