package com.moneylog.infra.web;

import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.sk.time.TimeExceptions.InvalidCompetenceException;
import com.moneylog.infra.exception.InvalidPathValueException;

public final class QueryMonths {

    private QueryMonths() {
    }

    public static Competence parse(String month, String code, String field) {
        if (month == null || !month.matches("\\d{4}-\\d{2}")) {
            throw new InvalidPathValueException(code, field, "Mês inválido: use o formato AAAA-MM.");
        }
        try {
            Competence competence = Competence.parse(month);
            if (!FinancialDates.isInWindow(competence.firstDay()) || !FinancialDates.isInWindow(competence.lastDay())) {
                throw new InvalidPathValueException(code, field, "Mês fora da janela de 2000-01 a 2100-12.");
            }
            return competence;
        } catch (InvalidCompetenceException e) {
            throw new InvalidPathValueException(code, field, "Mês inválido: use o formato AAAA-MM.");
        }
    }
}
