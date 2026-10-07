package com.moneylog.infra.card.controller;

import java.util.List;

import com.moneylog.core.finance.app.cards.PurchasePreviewView;
import com.moneylog.core.sk.money.Money;

/** Saída da prévia de compra (contrato: {@code PurchasePreview}). */
public record PurchasePreviewResponse(List<InstallmentResponse> installments, Money total, Money availableLimitAfter, boolean overLimit,
                                      List<String> warnings) {

    public record InstallmentResponse(int number, int total, Money amount, String competence, InvoiceRefResponse invoice,
                                      boolean lateEntry) {
    }

    public static PurchasePreviewResponse of(PurchasePreviewView view) {
        return new PurchasePreviewResponse(view.installments().stream()
                .map(i -> new InstallmentResponse(i.number(), i.total(), i.amount(), i.competence().toString(),
                        InvoiceRefResponse.of(i.invoice()), i.lateEntry()))
                .toList(), view.total(), view.availableLimitAfter(), view.overLimit(),
                view.warnings().stream().map(Enum::name).toList());
    }
}
