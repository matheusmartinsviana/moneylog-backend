package com.moneylog.infra.card.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.moneylog.core.finance.app.cards.usecase.PreviewCardPurchaseUseCase;
import com.moneylog.core.finance.app.cards.usecase.PreviewCardPurchaseUseCase.PreviewCardPurchaseQuery;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.security.CurrentUser;

@RestController
@RequestMapping("/v1/cards/{id}/purchase-preview")
public class CardPurchaseController {

    private final PreviewCardPurchaseUseCase preview;

    public CardPurchaseController(PreviewCardPurchaseUseCase preview) {
        this.preview = preview;
    }

    @PostMapping
    public PurchasePreviewResponse preview(@CurrentUser UUID userId, @PathVariable UUID id,
                                           @Valid @RequestBody PurchasePreviewRequest body) {
        return PurchasePreviewResponse.of(preview.handle(new PreviewCardPurchaseQuery(userId, id,
                Money.of(body.amount(), CurrencyCode.BRL), body.installments(), body.date())));
    }
}
