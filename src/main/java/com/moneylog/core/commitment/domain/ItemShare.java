package com.moneylog.core.commitment.domain;

import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record ItemShare(UUID personId, Money amount) {

    public boolean isOwner() {
        return personId == null;
    }
}
