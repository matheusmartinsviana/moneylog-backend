package com.moneylog.core.commitment.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.commitment.app.CommitmentView;
import com.moneylog.core.commitment.domain.ItemShare;

public interface UpdateCommitmentItemSharesUseCase {

    CommitmentView handle(UpdateCommitmentItemSharesCommand command);

    record UpdateCommitmentItemSharesCommand(UUID userId, UUID id, int position, List<ItemShare> shares, UUID coBuyerPersonId, long version) {
    }
}
