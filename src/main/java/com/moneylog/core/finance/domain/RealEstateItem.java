package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record RealEstateItem(UUID commitmentId, int position, LocalDate dueDate, Money amount, Money ownerShare, boolean sharesDefined) {

    public String key() {
        return commitmentId + "/" + position;
    }

    public Money mine() {
        return sharesDefined ? ownerShare : amount;
    }
}
