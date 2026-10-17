package com.moneylog.core.notification.app.gateways;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public interface AlertSourcesGateway {

    Created createdBetween(UUID userId, Instant from, Instant to);

    record NewPurchase(UUID transactionId, Money amount, int installments) {
    }

    record NewCashOut(UUID debtId, UUID transactionId) {
    }

    record Created(List<NewPurchase> purchases, List<NewCashOut> cashOuts, Set<UUID> recurrenceIds) {

        public static final Created NONE = new Created(List.of(), List.of(), Set.of());

        public Created {
            purchases = List.copyOf(purchases);
            cashOuts = List.copyOf(cashOuts);
            recurrenceIds = Set.copyOf(recurrenceIds);
        }
    }
}
