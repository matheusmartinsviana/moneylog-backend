package com.moneylog.infra.card.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.cards.usecase.PreviewBillingChangeUseCase;
import com.moneylog.core.finance.app.cards.usecase.PreviewBillingChangeUseCase.PreviewBillingChangeQuery;
import com.moneylog.infra.security.CurrentUser;

@RestController
@RequestMapping("/v1/cards/{id}/billing-change-preview")
public class CardBillingController {

    private final PreviewBillingChangeUseCase preview;

    public CardBillingController(PreviewBillingChangeUseCase preview) {
        this.preview = preview;
    }

    @GetMapping
    public BillingChangePreviewResponse preview(@CurrentUser UUID userId, @PathVariable UUID id, @RequestParam int closingDay,
                                                @RequestParam int dueDay) {
        return BillingChangePreviewResponse.of(preview.handle(new PreviewBillingChangeQuery(userId, id, closingDay, dueDay)));
    }
}
