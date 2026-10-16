package com.moneylog.infra.income.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.income.app.GeneratedIncomeOccurrence;
import com.moneylog.core.income.domain.PayrollKind;
import com.moneylog.core.sk.money.Money;

public record GeneratedIncomeOccurrencesResponse(List<Item> items) {

    public record Item(PayrollKind kind, String competence, LocalDate date, Money amount, UUID recurrenceId, boolean created) {
    }

    public static GeneratedIncomeOccurrencesResponse of(List<GeneratedIncomeOccurrence> generated) {
        return new GeneratedIncomeOccurrencesResponse(generated.stream()
                .map(g -> new Item(g.kind(), g.competence().toString(), g.date(), g.amount(), g.recurrenceId(), g.created())).toList());
    }
}
