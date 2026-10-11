package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.sk.money.Money;

public interface UpdateCardDetailsUseCase {

    CardDetails handle(UpdateCardDetailsCommand command);

    record UpdateCardDetailsCommand(UUID userId, UUID cardId, String name, String institution, Money limit, int closingDay,
                                    int dueDay, UUID defaultPaymentAccountId, java.time.LocalDate confirmedEffectiveFrom, long version) {

        public UpdateCardDetailsCommand(UUID userId, UUID cardId, String name, String institution, Money limit, int closingDay,
                                        int dueDay, UUID defaultPaymentAccountId, long version) {
            this(userId, cardId, name, institution, limit, closingDay, dueDay, defaultPaymentAccountId, null, version);
        }
    }
}
