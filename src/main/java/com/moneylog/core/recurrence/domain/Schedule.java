package com.moneylog.core.recurrence.domain;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.moneylog.core.recurrence.exception.RecurrenceExceptions.InvalidRecurrenceException;

/**
 * Agenda de uma recorrência: frequência, início e fim opcional (data ou quantidade). A ocorrência {@code n} é sempre
 * calculada a partir da <b>âncora</b> ({@code startDate}), nunca da anterior: mensal no dia 31 dá 31/01, 28/02, 31/03,
 * 30/04; anual em 29/02 dá 28/02 nos anos não bissextos (D1 §2.7).
 *
 * @param interval só em {@code CUSTOM} (≥ 1); nos demais é 1
 */
public record Schedule(Frequency frequency, int interval, IntervalUnit unit, LocalDate startDate, LocalDate endDate,
                       Integer maxOccurrences) {

    private static final int SAFETY_LIMIT = 100_000;

    public Schedule {
        if (frequency == null || startDate == null) {
            throw new InvalidRecurrenceException("frequência e início são obrigatórios.");
        }
        if (frequency == Frequency.CUSTOM) {
            if (interval < 1 || unit == null) {
                throw new InvalidRecurrenceException("a frequência personalizada exige intervalo ≥ 1 e unidade.");
            }
        } else if (interval != 1 || unit != null) {
            throw new InvalidRecurrenceException("intervalo e unidade só existem na frequência personalizada.");
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new InvalidRecurrenceException("o fim não pode ser anterior ao início.");
        }
        if (maxOccurrences != null && maxOccurrences < 1) {
            throw new InvalidRecurrenceException("a quantidade máxima de ocorrências precisa ser ≥ 1.");
        }
    }

    public static Schedule of(Frequency frequency, LocalDate startDate) {
        return new Schedule(frequency, 1, null, startDate, null, null);
    }

    public static Schedule custom(int interval, IntervalUnit unit, LocalDate startDate) {
        return new Schedule(Frequency.CUSTOM, interval, unit, startDate, null, null);
    }

    public Schedule until(LocalDate end) {
        return new Schedule(frequency, interval, unit, startDate, end, maxOccurrences);
    }

    public Schedule limitedTo(int max) {
        return new Schedule(frequency, interval, unit, startDate, endDate, max);
    }

    /** Ocorrência {@code n} (0 = início), calculada a partir da âncora. */
    public LocalDate nth(long n) {
        return switch (frequency) {
            case DAILY -> startDate.plusDays(n);
            case WEEKLY -> startDate.plusWeeks(n);
            case MONTHLY -> startDate.plusMonths(n);
            case YEARLY -> startDate.plusYears(n);
            case CUSTOM -> switch (unit) {
                case DAYS -> startDate.plusDays(n * interval);
                case WEEKS -> startDate.plusWeeks(n * interval);
                case MONTHS -> startDate.plusMonths(n * interval);
            };
        };
    }

    /** Datas agendadas em {@code [from, to]}, respeitando fim e quantidade máxima. */
    public List<LocalDate> datesBetween(LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        for (long n = 0; n < SAFETY_LIMIT; n++) {
            if (maxOccurrences != null && n >= maxOccurrences) {
                break;
            }
            LocalDate date = nth(n);
            if (date.isAfter(to) || (endDate != null && date.isAfter(endDate))) {
                break;
            }
            if (!date.isBefore(from)) {
                dates.add(date);
            }
        }
        return List.copyOf(dates);
    }

    /**
     * Existe ocorrência agendada em {@code date} ou depois? {@code false} = a agenda terminou (fim anterior a {@code date}
     * ou quantidade máxima esgotada). Sem fim nem quantidade é sempre {@code true}.
     */
    public boolean hasOccurrenceOnOrAfter(LocalDate date) {
        if (endDate == null && maxOccurrences == null) {
            return true;
        }
        for (long n = 0; n < SAFETY_LIMIT; n++) {
            if (maxOccurrences != null && n >= maxOccurrences) {
                return false;
            }
            LocalDate candidate = nth(n);
            if (endDate != null && candidate.isAfter(endDate)) {
                return false;
            }
            if (!candidate.isBefore(date)) {
                return true;
            }
        }
        return false;
    }

    public boolean isOccurrence(LocalDate date) {
        return datesBetween(date, date).contains(date);
    }

    /**
     * Fator de normalização para o mês, como fração exata {@code [numerador, denominador]}: semanal = 52/12,
     * diário = 365/12, anual = 1/12, mensal = 1; no personalizado divide pelo intervalo.
     */
    BigInteger[] monthlyFactor() {
        long numerator;
        long denominator;
        IntervalUnit effective = switch (frequency) {
            case DAILY -> IntervalUnit.DAYS;
            case WEEKLY -> IntervalUnit.WEEKS;
            case MONTHLY, YEARLY -> IntervalUnit.MONTHS;
            case CUSTOM -> unit;
        };
        switch (effective) {
            case DAYS -> { numerator = 365; denominator = 12; }
            case WEEKS -> { numerator = 52; denominator = 12; }
            default -> { numerator = 1; denominator = frequency == Frequency.YEARLY ? 12 : 1; }
        }
        return new BigInteger[] {BigInteger.valueOf(numerator), BigInteger.valueOf(denominator * interval)};
    }
}
