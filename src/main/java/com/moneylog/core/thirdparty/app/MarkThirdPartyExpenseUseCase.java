package com.moneylog.core.thirdparty.app;

import java.util.UUID;

import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;

public interface MarkThirdPartyExpenseUseCase {

    ThirdPartyExpense handle(MarkThirdPartyExpenseCommand command);

    record MarkThirdPartyExpenseCommand(UUID userId, UUID transactionId, UUID personId, String note, com.moneylog.core.sk.money.Money amount,
                                        UUID idempotencyKey) {
    }

    String IDEMPOTENCY_SCOPE = "POST /v1/third-party-expenses";
}
