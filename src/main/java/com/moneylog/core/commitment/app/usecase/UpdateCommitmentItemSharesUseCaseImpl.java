package com.moneylog.core.commitment.app.usecase;

import java.util.Objects;

import com.moneylog.core.commitment.app.CommitmentPersons;
import com.moneylog.core.commitment.app.CommitmentView;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.commitment.domain.ItemShare;
import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.commitment.exception.CommitmentExceptions.CommitmentNotFoundException;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Versioned;

public class UpdateCommitmentItemSharesUseCaseImpl implements UpdateCommitmentItemSharesUseCase {

    private final CommitmentGateway commitments;
    private final PersonGateway persons;

    public UpdateCommitmentItemSharesUseCaseImpl(CommitmentGateway commitments, PersonGateway persons) {
        this.commitments = commitments;
        this.persons = persons;
    }

    @Override
    public CommitmentView handle(UpdateCommitmentItemSharesCommand c) {
        commitments.lockForUpdate(c.userId(), c.id());
        Versioned<TrackedCommitment> current = commitments.findById(c.userId(), c.id()).orElseThrow(() -> new CommitmentNotFoundException(c.id()));
        if (current.version() != c.version()) {
            throw new VersionConflictException(c.version(), current.version());
        }
        CommitmentPersons.requireOwned(persons, c.userId(), c.shares().stream().map(ItemShare::personId).filter(Objects::nonNull).toList());
        TrackedCommitment commitment = current.value();
        commitment.updateItemShares(c.position(), c.shares(), c.coBuyerPersonId());
        return new CommitmentView(commitments.update(commitment, c.version()));
    }
}
