package com.moneylog.core.debt.app;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.domain.PersonBalance;
import com.moneylog.core.sk.persistence.Versioned;

/** Monta {@link PersonView}: lê as dívidas das pessoas de uma vez (sem consulta por item) e calcula o saldo de cada uma. */
public final class PersonViews {

    private PersonViews() {
    }

    /** Pessoas com saldo; {@code withDebts} acrescenta o histórico (detalhe). */
    public static List<PersonView> of(DebtGateway debts, UUID userId, List<Versioned<Person>> people, LocalDate today,
                                      boolean withDebts) {
        if (people.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Debt>> byPerson = new HashMap<>();
        for (Versioned<Debt> stored : debts.findByPersons(userId, people.stream().map(p -> p.value().id()).toList())) {
            byPerson.computeIfAbsent(stored.value().personId(), id -> new ArrayList<>()).add(stored.value());
        }
        List<PersonView> views = new ArrayList<>();
        for (Versioned<Person> stored : people) {
            Person person = stored.value();
            List<Debt> own = byPerson.getOrDefault(person.id(), List.of());
            List<DebtSummary> summaries = withDebts
                    ? own.stream().sorted(Comparator.comparing(Debt::date).thenComparing(Debt::id).reversed())
                            .map(d -> DebtSummary.of(d, person, today)).toList()
                    : List.of();
            views.add(new PersonView(stored, PersonBalance.of(own), summaries));
        }
        return views;
    }

    public static PersonView one(DebtGateway debts, UUID userId, Versioned<Person> person, LocalDate today, boolean withDebts) {
        return of(debts, userId, List.of(person), today, withDebts).get(0);
    }
}
