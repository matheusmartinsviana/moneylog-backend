package com.moneylog.core.recurrence.app;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharePersonArchivedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharesInvalidException;

public final class RecurrenceShares {

    private RecurrenceShares() {
    }

    public static void requireUsable(PersonGateway persons, UUID userId, List<ShareLine> lines) {
        Set<UUID> ids = lines == null ? Set.of() : lines.stream().map(ShareLine::personId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        if (persons == null) {
            throw new SharesInvalidException("o cadastro de pessoas não está disponível.");
        }
        Map<UUID, Person> found = persons.findByIds(userId, ids);
        for (UUID id : ids) {
            Person person = found.get(id);
            if (person == null) {
                throw new PersonNotFoundException(id);
            }
            if (person.isArchived()) {
                throw new SharePersonArchivedException();
            }
        }
    }
}
