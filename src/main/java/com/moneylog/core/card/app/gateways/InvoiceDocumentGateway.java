package com.moneylog.core.card.app.gateways;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.sk.money.Money;

public interface InvoiceDocumentGateway {

    record Stored(Money amount, long version) {
    }

    Stored find(UUID userId, UUID invoiceId);

    Map<UUID, Money> amountsOf(UUID userId);

    Stored change(UUID userId, CreditCard card, LocalDate closingDate, Money amountOrNull);
}
