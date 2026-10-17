package com.moneylog.core.commitment.app.usecase;

import java.util.UUID;

import com.moneylog.core.commitment.app.CommitmentByPersonView;

public interface GetCommitmentByPersonUseCase {

    CommitmentByPersonView handle(GetCommitmentByPersonQuery query);

    record GetCommitmentByPersonQuery(UUID userId, UUID id) {
    }
}
