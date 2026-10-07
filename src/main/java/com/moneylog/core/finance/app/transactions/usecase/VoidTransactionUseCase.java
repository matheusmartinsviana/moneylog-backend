package com.moneylog.core.finance.app.transactions.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;

public interface VoidTransactionUseCase {

    /** Motivo gravado nos lançamentos desativados pelo estorno. */
    String VOID_REASON = "VOID";

    /**
     * Estorna a movimentação pelo ponto único {@code TransactionVoiding} (S-36): ela vira {@code VOIDED}, com motivo e
     * instante, e os lançamentos ficam inativos. Recusas: já estornada, conta arquivada ({@code ACCOUNT_ARCHIVED}, S-73)
     * e despesa com reembolso ativo ({@code TRANSACTION_HAS_ACTIVE_REFUNDS}, S-89).
     */
    TransactionDetails handle(VoidTransactionCommand command);

    record VoidTransactionCommand(UUID userId, UUID transactionId, String reason) {
    }
}
