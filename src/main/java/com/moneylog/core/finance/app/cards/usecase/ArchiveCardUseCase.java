package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.CardDetails;

public interface ArchiveCardUseCase {

    /**
     * Arquiva o cartão se não houver fatura com saldo em aberto, crédito transportado nem parcela futura não cancelada
     * (S-120, D2 §4.3); senão {@code CARD_HAS_PENDING_BALANCE}. Já arquivado: {@code CARD_ALREADY_ARCHIVED}.
     */
    CardDetails handle(ArchiveCardCommand command);

    record ArchiveCardCommand(UUID userId, UUID cardId) {
    }
}
