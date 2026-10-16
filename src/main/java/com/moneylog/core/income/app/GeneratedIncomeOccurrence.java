package com.moneylog.core.income.app;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.income.domain.PayrollKind;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record GeneratedIncomeOccurrence(PayrollKind kind, Competence competence, LocalDate date, Money amount, UUID recurrenceId,
                                        boolean created) {
}
