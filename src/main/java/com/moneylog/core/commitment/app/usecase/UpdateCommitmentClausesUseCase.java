package com.moneylog.core.commitment.app.usecase;

import java.util.UUID;

import com.moneylog.core.commitment.app.CommitmentView;
import com.moneylog.core.commitment.domain.CommitmentClauses;

public interface UpdateCommitmentClausesUseCase {

    CommitmentView handle(UpdateCommitmentClausesCommand command);

    record UpdateCommitmentClausesCommand(UUID userId, UUID id, CommitmentClauses clauses, long version) {
    }
}
