package com.moneylog.core.commitment.app.usecase;

import java.util.Map;
import java.util.stream.Collectors;

import com.moneylog.core.commitment.app.CommitmentByPersonView;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.commitment.domain.CommitmentKind;
import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.commitment.exception.CommitmentExceptions.CommitmentNotFoundException;
import com.moneylog.core.commitment.exception.CommitmentExceptions.InvalidCommitmentException;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Person;

public class GetCommitmentByPersonUseCaseImpl implements GetCommitmentByPersonUseCase {

    private final CommitmentGateway commitments;
    private final PersonGateway persons;

    public GetCommitmentByPersonUseCaseImpl(CommitmentGateway commitments, PersonGateway persons) {
        this.commitments = commitments;
        this.persons = persons;
    }

    @Override
    public CommitmentByPersonView handle(GetCommitmentByPersonQuery q) {
        TrackedCommitment commitment = commitments.findById(q.userId(), q.id()).orElseThrow(() -> new CommitmentNotFoundException(q.id()))
                .value();
        if (commitment.kind() != CommitmentKind.LONG_TERM_PROJECT) {
            throw new InvalidCommitmentException("só o projeto de longo prazo tem o recorte por pessoa.");
        }
        Map<java.util.UUID, String> names = persons.findByIds(q.userId(), commitment.personIds()).values().stream()
                .collect(Collectors.toMap(Person::id, Person::name));
        return new CommitmentByPersonView(commitment, commitment.totalsByPerson(), commitment.undefinedSharePositions(), names);
    }
}
