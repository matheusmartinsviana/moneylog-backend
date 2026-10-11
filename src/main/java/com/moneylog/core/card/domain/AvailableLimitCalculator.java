package com.moneylog.core.card.domain;

import java.util.Collection;
import java.util.Objects;

import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.Money;

public final class AvailableLimitCalculator {

    private AvailableLimitCalculator() {
    }

    public static AvailableLimit calculate(CreditCard card, Collection<Posting> cardPostings) {
        Objects.requireNonNull(card, "card");
        Money balance = Money.zero(card.currency());
        for (Posting posting : cardPostings) {
            if (posting.bookType() == BookType.CARD && card.id().equals(posting.book().id())) {
                balance = balance.add(posting.amount());
            }
        }
        Money credit = balance.isPositive() ? balance : Money.zero(card.currency());
        if (card.limit() == null) {
            return new AvailableLimit(null, false, credit);
        }
        Money available = card.limit().add(balance);
        return new AvailableLimit(available, available.isNegative(), credit);
    }
}
