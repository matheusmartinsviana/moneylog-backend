package com.moneylog.core.purchasegroup.app.usecase;

import java.util.List;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.purchasegroup.app.PurchaseGroupView;
import com.moneylog.core.purchasegroup.app.PurchaseGroupViews;
import com.moneylog.core.purchasegroup.app.gateways.PurchaseGroupGateway;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.TimeProvider;

public class ListPurchaseGroupsUseCaseImpl implements ListPurchaseGroupsUseCase {

    private final PurchaseGroupGateway groups;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public ListPurchaseGroupsUseCaseImpl(PurchaseGroupGateway groups, FinancialRecordsGateway records, TimeProvider time) {
        this.groups = groups;
        this.records = records;
        this.time = time;
    }

    @Override
    public Slice<PurchaseGroupView> handle(ListPurchaseGroupsQuery query) {
        PurchaseGroupView.Status wanted = query.status() == null ? PurchaseGroupView.Status.ACTIVE : query.status();
        List<PurchaseGroupView> all = PurchaseGroupViews.ofAll(groups.findAll(query.userId()), records.load(query.userId()), time.today())
                .stream().filter(v -> v.status() == wanted).toList();
        PageQuery page = query.page();
        if (page.isBeyondAnyData() || page.offset() >= all.size()) {
            return Slice.empty();
        }
        int from = (int) page.offset();
        int to = Math.min(all.size(), from + page.pageSize());
        return new Slice<>(all.subList(from, to), to < all.size());
    }
}
