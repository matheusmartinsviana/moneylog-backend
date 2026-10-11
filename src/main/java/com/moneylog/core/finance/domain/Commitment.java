package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;

public record Commitment(CommitmentKind kind, LocalDate dueDate, Money amount, FlowDirection direction, Bucket bucket, String label,
                         UUID refId, LocalDate refDate, List<Commitment> items, boolean informational,
                         Integer installmentNumber, Integer installmentTotal) {

    public Commitment {
        items = List.copyOf(items);
    }

    public Commitment(CommitmentKind kind, LocalDate dueDate, Money amount, FlowDirection direction, Bucket bucket, String label,
                      UUID refId, LocalDate refDate, List<Commitment> items, boolean informational) {
        this(kind, dueDate, amount, direction, bucket, label, refId, refDate, items, informational, null, null);
    }
}
