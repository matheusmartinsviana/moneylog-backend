package com.moneylog.core.card.domain;

import com.moneylog.core.sk.money.Money;

public record AvailableLimit(Money available, boolean overLimit, Money creditBalance) {
}
