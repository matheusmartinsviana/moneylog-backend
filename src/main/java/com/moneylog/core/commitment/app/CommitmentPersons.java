package com.moneylog.core.commitment.app;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.commitment.exception.CommitmentExceptions.CommitmentPersonInvalidException;
import com.moneylog.core.debt.app.gateways.PersonGateway;

public final class CommitmentPersons {

    private CommitmentPersons() {
    }

    public static void requireOwned(PersonGateway persons, UUID userId, Collection<UUID> personIds) {
        Set<UUID> wanted = Set.copyOf(personIds);
        if (wanted.isEmpty()) {
            return;
        }
        if (persons.findByIds(userId, wanted).size() != wanted.size()) {
            throw new CommitmentPersonInvalidException();
        }
    }
}
