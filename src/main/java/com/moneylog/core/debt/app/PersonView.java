package com.moneylog.core.debt.app;

import java.time.Instant;
import java.util.List;

import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.domain.PersonBalance;
import com.moneylog.core.sk.persistence.Versioned;

/**
 * Pessoa como a API mostra: o cadastro, o saldo calculado ({@link PersonBalance}) e, no detalhe, o histórico de dívidas
 * (abertas e encerradas, da mais recente para a mais antiga). {@code debts} é vazio nas listas e nas respostas de escrita.
 */
public record PersonView(Versioned<Person> stored, PersonBalance balance, List<DebtSummary> debts) {

    public PersonView {
        debts = debts == null ? List.of() : List.copyOf(debts);
    }

    public Person person() {
        return stored.value();
    }

    public Instant createdAt() {
        return stored.createdAt();
    }

    public Instant updatedAt() {
        return stored.updatedAt();
    }
}
