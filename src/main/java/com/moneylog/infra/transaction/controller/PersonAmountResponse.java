package com.moneylog.infra.transaction.controller;

import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PersonAmount;

public record PersonAmountResponse(UUID personId, Money amount) {

    public static PersonAmountResponse of(PersonAmount part) {
        return new PersonAmountResponse(part.personId(), part.amount());
    }
}
