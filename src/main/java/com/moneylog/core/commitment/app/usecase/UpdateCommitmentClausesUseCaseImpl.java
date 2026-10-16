package com.moneylog.core.commitment.app.usecase;

import com.moneylog.core.commitment.app.CommitmentView;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.commitment.exception.CommitmentExceptions.CommitmentNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Versioned;

public class UpdateCommitmentClausesUseCaseImpl implements UpdateCommitmentClausesUseCase {

    private final CommitmentGateway commitments;

    public UpdateCommitmentClausesUseCaseImpl(CommitmentGateway commitments) {
        this.commitments = commitments;
    }

    @Override
    public CommitmentView handle(UpdateCommitmentClausesCommand c) {
        commitments.lockForUpdate(c.userId(), c.id());
        Versioned<TrackedCommitment> current = commitments.findById(c.userId(), c.id()).orElseThrow(() -> new CommitmentNotFoundException(c.id()));
        if (current.version() != c.version()) {
            throw new VersionConflictException(c.version(), current.version());
        }
        TrackedCommitment commitment = current.value();
        commitment.defineClauses(c.clauses());
        return new CommitmentView(commitments.update(commitment, c.version()));
    }
}
