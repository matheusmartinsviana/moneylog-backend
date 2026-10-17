package com.moneylog.core.transaction.domain;

import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record PersonAmount(UUID personId, Money amount) {
}
