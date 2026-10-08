package com.moneylog.core.commitment.app.usecase;

import com.moneylog.core.commitment.app.CommitmentView;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.commitment.exception.CommitmentExceptions.CommitmentNotFoundException;

public class GetCommitmentUseCaseImpl implements GetCommitmentUseCase {

    private final CommitmentGateway commitments;

    public GetCommitmentUseCaseImpl(CommitmentGateway commitments) {
        this.commitments = commitments;
    }

    @Override
    public CommitmentView handle(GetCommitmentQuery q) {
        return new CommitmentView(commitments.findById(q.userId(), q.id()).orElseThrow(() -> new CommitmentNotFoundException(q.id())));
    }
}
