package com.moneylog.core.finance.app.cards;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.moneylog.core.card.domain.AvailableLimit;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.sk.money.Money;

public record CardDetails(CreditCard card, Instant createdAt, Instant updatedAt, AvailableLimit limit, Money creditBalance,
                          InvoiceView currentInvoice, Money totalCommitted, Money committedFuture, BigDecimal usedRatio,
                          boolean archivable) {

    public CardDetails {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(creditBalance, "creditBalance");
        Objects.requireNonNull(currentInvoice, "currentInvoice");
        Objects.requireNonNull(totalCommitted, "totalCommitted");
        Objects.requireNonNull(committedFuture, "committedFuture");
    }
}
