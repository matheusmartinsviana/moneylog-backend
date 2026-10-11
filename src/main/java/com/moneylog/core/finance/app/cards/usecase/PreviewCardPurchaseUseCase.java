package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.cards.PurchasePreviewView;
import com.moneylog.core.sk.money.Money;

public interface PreviewCardPurchaseUseCase {

    PurchasePreviewView handle(PreviewCardPurchaseQuery query);

    record PreviewCardPurchaseQuery(UUID userId, UUID cardId, Money amount, int installments, LocalDate date) {
    }
}
