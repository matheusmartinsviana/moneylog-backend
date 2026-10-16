package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.sk.money.Money;

public interface CreateCardCashOutUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/card-cash-outs";

    DebtView handle(CreateCardCashOutCommand command);

    record CreateCardCashOutCommand(UUID userId, UUID cardId, UUID accountId, Money principal, int installments,
                                    Money totalWithInterest, Integer rateBps, LocalDate firstInvoiceDate, UUID beneficiaryPersonId,
                                    UUID interestCategoryId, String channel, UUID idempotencyKey) {
    }
}
