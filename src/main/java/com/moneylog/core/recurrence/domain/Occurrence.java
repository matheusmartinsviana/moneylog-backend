package com.moneylog.core.recurrence.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;

public record Occurrence(UUID recurrenceId, LocalDate date, Money amount, FlowDirection direction, String description) {
}
