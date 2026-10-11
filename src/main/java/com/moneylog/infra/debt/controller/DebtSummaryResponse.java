package com.moneylog.infra.debt.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.debt.app.DebtSummary;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.sk.money.Money;

public record DebtSummaryResponse(UUID id, PersonRefResponse person, String creditorName, DebtDirection direction, DebtStatus status, Money principal,
                                  Money settled, Money outstanding, LocalDate date, LocalDate expectedDate, boolean overdue,
                                  String description) {

    public static DebtSummaryResponse of(DebtSummary d) {
        return new DebtSummaryResponse(d.id(), d.personId() == null ? null : new PersonRefResponse(d.personId(), d.personName()),
                d.personId() == null ? d.personName() : null, d.direction(), d.status(),
                d.principal(), d.settled(), d.outstanding(), d.date(), d.expectedDate(), d.overdue(), d.description());
    }
}
