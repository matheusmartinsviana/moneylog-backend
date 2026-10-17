package com.moneylog.core.thirdparty.app;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;

public interface RecordThirdPartyReceiptUseCase {

    ThirdPartyReceipt handle(RecordThirdPartyReceiptCommand command);

    record RecordThirdPartyReceiptCommand(UUID userId, UUID personId, UUID transactionId, Money amount, LocalDate receivedOn, String note,
                                          UUID accountId, UUID idempotencyKey) {
    }

    String IDEMPOTENCY_SCOPE = "POST /v1/third-party-receipts";
}
