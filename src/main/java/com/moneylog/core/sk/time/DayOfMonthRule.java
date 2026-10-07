package com.moneylog.core.sk.time;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

import com.moneylog.core.sk.time.TimeExceptions.InvalidDayOfMonthException;

/**
 * Dia do mês de 1 a 31 com "clamp" para o último dia do mês (D1 §2.1): dia 31 em fevereiro de 2027 é 28/02/2027
 * e em fevereiro de 2028 é 29/02/2028. Usado no fechamento/vencimento do cartão e na recorrência.
 */
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
