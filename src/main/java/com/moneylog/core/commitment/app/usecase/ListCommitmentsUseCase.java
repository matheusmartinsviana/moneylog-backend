package com.moneylog.core.commitment.app.usecase;

import java.util.UUID;

import com.moneylog.core.commitment.app.CommitmentView;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListCommitmentsUseCase {

    Slice<CommitmentView> handle(ListCommitmentsQuery query);

    record ListCommitmentsQuery(UUID userId, PageQuery page) {
    }
}
