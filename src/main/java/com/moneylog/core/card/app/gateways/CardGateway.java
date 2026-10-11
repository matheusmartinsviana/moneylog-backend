package com.moneylog.core.card.app.gateways;

import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Persisted;

public interface CardGateway {

    Persisted<CreditCard> save(CreditCard card);

    void lockForUpdate(UUID userId, UUID id);

    void discardReadState();

    record BillingDays(int closingDay, int dueDay) {
    }

    Optional<BillingDays> currentBillingDays(UUID userId, UUID id);

    Optional<Persisted<CreditCard>> findById(UUID userId, UUID id);

    Slice<Persisted<CreditCard>> list(UUID userId, CardStatus status, PageQuery page);

    void refreshInvoiceRows(UUID userId, CreditCard card);
}
