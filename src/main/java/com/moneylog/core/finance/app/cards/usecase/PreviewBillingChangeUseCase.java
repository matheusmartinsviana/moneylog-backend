package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.cards.BillingChangePreviewView;

public interface PreviewBillingChangeUseCase {

    BillingChangePreviewView handle(PreviewBillingChangeQuery query);

    record PreviewBillingChangeQuery(UUID userId, UUID cardId, int closingDay, int dueDay) {
    }
}
