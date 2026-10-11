package com.moneylog.infra.dashboard.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.dashboard.UpcomingView;
import com.moneylog.core.finance.domain.Bucket;
import com.moneylog.core.finance.domain.Commitment;
import com.moneylog.core.finance.domain.CommitmentKind;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;

public record UpcomingResponse(List<Item> items, List<Item> undated, Totals totals, boolean overdueTruncated) {

    public record Installment(int number, int total) {
    }

    public record Child(String kind, String label, Installment installment, Money amount, boolean informational) {
    }

    public record Item(CommitmentKind kind, Bucket bucket, LocalDate dueDate, FlowDirection direction, Money amount, String label,
                       String refType, UUID refId, List<Child> items) {
    }

    public record Totals(Money out, Money in) {
    }

    public static UpcomingResponse of(UpcomingView view) {
        return new UpcomingResponse(view.items().stream().map(UpcomingResponse::item).toList(),
                view.undated().stream().map(UpcomingResponse::item).toList(), new Totals(view.out(), view.in()),
                view.overdueTruncated());
    }

    private static Item item(Commitment c) {
        return new Item(c.kind(), c.bucket(), c.dueDate(), c.direction(), c.amount(), c.label(), refType(c.kind()), c.refId(),
                c.items().stream().map(UpcomingResponse::child).toList());
    }

    private static Child child(Commitment c) {
        return new Child(c.kind().name(), c.label(), new Installment(c.installmentNumber(), c.installmentTotal()), c.amount(), c.informational());
    }

    private static String refType(CommitmentKind kind) {
        return switch (kind) {
            case INVOICE, INVOICE_FORECAST, INSTALLMENT -> "INVOICE";
            case DEBT_PAYABLE, DEBT_RECEIVABLE -> "DEBT";
            case RECURRENCE -> "RECURRENCE";
            case SCHEDULED_TRANSACTION -> "TRANSACTION";
        };
    }
}
