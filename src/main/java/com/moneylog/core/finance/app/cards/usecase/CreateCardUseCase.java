package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.sk.money.Money;

public interface CreateCardUseCase {

    CardDetails handle(CreateCardCommand command);

    record CreateCardCommand(UUID userId, String name, String institution, Money limit, int closingDay, int dueDay,
                             UUID defaultPaymentAccountId) {
    }
}
