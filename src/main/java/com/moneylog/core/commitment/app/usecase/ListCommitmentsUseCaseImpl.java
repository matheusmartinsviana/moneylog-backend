package com.moneylog.core.commitment.app.usecase;

import com.moneylog.core.commitment.app.CommitmentView;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.sk.page.Slice;

public class ListCommitmentsUseCaseImpl implements ListCommitmentsUseCase {

    private final CommitmentGateway commitments;

    public ListCommitmentsUseCaseImpl(CommitmentGateway commitments) {
        this.commitments = commitments;
    }

    @Override
    public Slice<CommitmentView> handle(ListCommitmentsQuery q) {
        Slice<com.moneylog.core.sk.persistence.Versioned<com.moneylog.core.commitment.domain.TrackedCommitment>> page =
                commitments.list(q.userId(), q.page());
        return new Slice<>(page.items().stream().map(CommitmentView::new).toList(), page.hasNext());
    }
}
