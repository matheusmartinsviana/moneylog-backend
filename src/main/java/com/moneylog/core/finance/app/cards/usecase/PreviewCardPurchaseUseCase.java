package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.cards.PurchasePreviewView;
import com.moneylog.core.sk.money.Money;

public interface PreviewCardPurchaseUseCase {

    /**
     * O que a compra geraria (parcelas, competências, faturas alvo, lançamento tardio, limite depois), pelo <b>mesmo
     * caminho de código</b> da criação e sem gravar nada (S-124). Compra acima do limite não é recusada: {@code overLimit}
     * e o aviso {@code OVER_LIMIT} (S-125).
     *
     * @throws com.moneylog.core.card.exception.CardExceptions.CardNotFoundException cartão inexistente ou de outro usuário
     * @throws com.moneylog.core.card.exception.CardExceptions.CardArchivedException  cartão arquivado
     */
    PurchasePreviewView handle(PreviewCardPurchaseQuery query);

    record PreviewCardPurchaseQuery(UUID userId, UUID cardId, Money amount, int installments, LocalDate date) {
    }
}
