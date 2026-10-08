package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.CardDetails;

public interface SetClosingConfirmationUseCase {

    /**
     * Marca se o dia de fechamento do cartão está confirmado por documento (ADR-014). Com {@code false} o número do fechamento é só um
     * parâmetro técnico provisório e a tela mostra "fechamento não confirmado". Não muda o calendário nem as faturas. Versão diferente → 409.
     */
    CardDetails handle(SetClosingConfirmationCommand command);

    record SetClosingConfirmationCommand(UUID userId, UUID cardId, boolean confirmed, long version) {
    }
}
