package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.BillingChangePreviewView;

public interface PreviewBillingChangeUseCase {

    /**
     * O que mudar os dias de fechamento/vencimento faria (S-42, S-128), pelo mesmo caminho da mudança real
     * ({@code CardBillingChanges.changeBillingDays}) e sem gravar nada.
     *
     * @throws com.moneylog.core.card.exception.CardExceptions.InvalidBillingDayException dia fora de 1..31
     * @throws com.moneylog.core.card.exception.CardExceptions.CardNotFoundException      cartão inexistente ou de outro usuário
     */
    BillingChangePreviewView handle(PreviewBillingChangeQuery query);

    record PreviewBillingChangeQuery(UUID userId, UUID cardId, int closingDay, int dueDay) {
    }
}
