package com.moneylog.core.finance.app.dashboard;

import java.util.List;

import com.moneylog.core.finance.domain.Commitment;
import com.moneylog.core.sk.money.Money;

public record UpcomingView(List<Commitment> items, List<Commitment> undated, Money out, Money in, boolean overdueTruncated) {

    public UpcomingView {
        items = List.copyOf(items);
        undated = List.copyOf(undated);
    }

    public UpcomingView(List<Commitment> items, List<Commitment> undated, Money out, Money in) {
        this(items, undated, out, in, false);
    }
}
