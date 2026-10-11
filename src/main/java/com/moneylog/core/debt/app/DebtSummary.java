package com.moneylog.core.debt.app;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.sk.money.Money;

public record DebtSummary(UUID id, UUID personId, String personName, DebtDirection direction, DebtStatus status, Money principal,
                          Money settled, Money outstanding, LocalDate date, LocalDate expectedDate, boolean overdue,
                          String description) {

    public static DebtSummary of(Debt debt, Person person, LocalDate today) {
        return new DebtSummary(debt.id(), person == null ? null : person.id(), person == null ? debt.creditorName() : person.name(), debt.direction(), debt.status(), debt.principal(), debt.settled(),
                debt.outstanding(), debt.date(), debt.expectedDate(), debt.isOverdue(today), debt.description());
    }
}
