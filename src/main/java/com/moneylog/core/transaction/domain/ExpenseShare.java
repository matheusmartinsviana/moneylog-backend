package com.moneylog.core.transaction.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.sk.money.Money;

public record ExpenseShare(UUID debtId, Person person, Money amount, LocalDate expectedDate, String description) {
}
