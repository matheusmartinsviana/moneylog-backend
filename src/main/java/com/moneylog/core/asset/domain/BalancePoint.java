package com.moneylog.core.asset.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import com.moneylog.core.sk.money.Money;

public record BalancePoint(Money balance, LocalDate date, Instant recordedAt) {

    public BalancePoint {
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
