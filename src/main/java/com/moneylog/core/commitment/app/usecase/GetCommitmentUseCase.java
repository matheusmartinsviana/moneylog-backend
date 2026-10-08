package com.moneylog.core.commitment.app.usecase;

import java.util.UUID;

import com.moneylog.core.commitment.app.CommitmentView;

public interface GetCommitmentUseCase {

    CommitmentView handle(GetCommitmentQuery query);

    record GetCommitmentQuery(UUID userId, UUID id) {
    }
}
