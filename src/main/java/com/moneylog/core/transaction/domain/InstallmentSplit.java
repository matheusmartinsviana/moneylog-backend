package com.moneylog.core.transaction.domain;

import java.util.List;

import com.moneylog.core.sk.money.Money;

public record InstallmentSplit(Money mine, List<PersonAmount> byPerson) {

    public InstallmentSplit {
        byPerson = List.copyOf(byPerson);
    }

    public Money thirdParties() {
        return Money.sum(byPerson.stream().map(PersonAmount::amount).toList(), mine.currency());
    }
}
