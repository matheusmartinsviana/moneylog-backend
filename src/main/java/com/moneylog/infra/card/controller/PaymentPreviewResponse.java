package com.moneylog.infra.card.controller;

import com.moneylog.core.finance.app.cards.PaymentPreviewView;
import com.moneylog.core.sk.money.Money;

public record PaymentPreviewResponse(String kind, Money outstandingAfter, Money creditCarryAfter) {

    public static PaymentPreviewResponse of(PaymentPreviewView view) {
        return new PaymentPreviewResponse(view.kind().name(), view.outstandingAfter(), view.creditCarryAfter());
    }
}
