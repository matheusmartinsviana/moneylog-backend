package com.moneylog.core.purchasegroup.app.usecase;

import java.util.UUID;

import com.moneylog.core.purchasegroup.app.PurchaseGroupView;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListPurchaseGroupsUseCase {

    Slice<PurchaseGroupView> handle(ListPurchaseGroupsQuery query);

    record ListPurchaseGroupsQuery(UUID userId, PurchaseGroupView.Status status, PageQuery page) {
    }
}
