package com.moneylog.core.sk.time;

import com.moneylog.core.sk.exception.BusinessRuleException;

public final class TimeExceptions {

    private TimeExceptions() {
    }

    public static class InvalidDayOfMonthException extends BusinessRuleException {
        public InvalidDayOfMonthException(int day) {
            super("INVALID_DAY_OF_MONTH", "Dia do mês inválido: " + day + ". Use um valor de 1 a 31.");
        }
    }

    public static class InvalidCompetenceException extends BusinessRuleException {
        public InvalidCompetenceException(String value) {
            super("INVALID_COMPETENCE", "Competência inválida: '" + value + "'. Use o formato AAAA-MM.");
        }
    }
}
