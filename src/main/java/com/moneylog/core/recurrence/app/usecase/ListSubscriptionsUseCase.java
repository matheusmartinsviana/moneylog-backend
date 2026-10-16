package com.moneylog.core.recurrence.app.usecase;

import java.util.UUID;

import com.moneylog.core.recurrence.app.SubscriptionListing;
import com.moneylog.core.recurrence.app.SubscriptionStatus;
import com.moneylog.core.recurrence.domain.RecurrenceScope;
import com.moneylog.core.sk.page.PageQuery;

public interface ListSubscriptionsUseCase {

    SubscriptionListing handle(ListSubscriptionsQuery query);

    record ListSubscriptionsQuery(UUID userId, SubscriptionStatus status, RecurrenceScope scope, PageQuery page) {

        public ListSubscriptionsQuery {
            status = status == null ? SubscriptionStatus.ACTIVE : status;
        }
    }
}
