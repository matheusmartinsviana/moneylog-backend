package com.moneylog.core.commitment.domain;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record PersonTotals(UUID personId, List<PersonItem> items, Money paid, Money pending) {

    public record PersonItem(int position, String label, ScheduleItemType type, java.time.LocalDate dueDate, Money amount, boolean paid,
                             boolean estimated) {
    }

    public Money total() {
        return paid.add(pending);
    }
}
