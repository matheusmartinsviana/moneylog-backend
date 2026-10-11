package com.moneylog.core.transaction.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.debt.domain.Person;

public record NewDebt(UUID debtId, Person person, LocalDate expectedDate, String description) {
}
