package com.moneylog.core.thirdparty.app;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;

public interface RecordThirdPartyReceiptUseCase {

    /**
     * Registra um recebimento de terceiro que JÁ está refletido no saldo observado, SEM movimento de conta (nada é lançado no razão). Com
     * {@code transactionId} liga-se a uma despesa marcada como de terceiro (reduz o pendente dela); sem, fica sem vínculo ("origem a
     * confirmar"). Nunca é renda. Mesma chave + mesmo corpo devolve o mesmo registro.
     */
    ThirdPartyReceipt handle(RecordThirdPartyReceiptCommand command);

    record RecordThirdPartyReceiptCommand(UUID userId, UUID personId, UUID transactionId, Money amount, LocalDate receivedOn, String note,
                                          UUID idempotencyKey) {
    }

    String IDEMPOTENCY_SCOPE = "POST /v1/third-party-receipts";
}
