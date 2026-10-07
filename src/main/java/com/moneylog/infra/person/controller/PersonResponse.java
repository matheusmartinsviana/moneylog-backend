package com.moneylog.infra.person.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.moneylog.core.debt.app.PersonView;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.domain.PersonBalance;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.debt.controller.DebtSummaryResponse;

/**
 * {@code Person} e {@code PersonDetail} do contrato: o detalhe traz {@code debts}; a lista e as respostas de escrita não
 * têm o campo ({@code NON_NULL}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonResponse(UUID id, String name, @JsonInclude(JsonInclude.Include.ALWAYS) String notes, boolean archived, Money net,
                             PersonBalance.Direction direction, int openDebts,
                             @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate nextDueDate, List<DebtSummaryResponse> debts,
                             long version, Instant createdAt, Instant updatedAt) {

    public static PersonResponse of(PersonView view) {
        return build(view, null);
    }

    public static PersonResponse detail(PersonView view) {
        return build(view, view.debts().stream().map(DebtSummaryResponse::of).toList());
    }

    private static PersonResponse build(PersonView view, List<DebtSummaryResponse> debts) {
        Person p = view.person();
        PersonBalance b = view.balance();
        return new PersonResponse(p.id(), p.name(), p.notes(), p.isArchived(), b.net(), b.direction(), b.openDebts(), b.nextDueDate(),
                debts, view.stored().version(), view.createdAt(), view.updatedAt());
    }
}
