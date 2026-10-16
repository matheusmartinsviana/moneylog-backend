package com.moneylog.infra.card.controller;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moneylog.core.finance.app.cards.PurchasePreviewView;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.infra.transaction.controller.PersonAmountResponse;
import com.moneylog.infra.transaction.controller.PersonShareLineResponse;

public record PurchasePreviewResponse(List<InstallmentResponse> installments, Money total, Money availableLimitAfter, boolean overLimit,
                                      List<String> warnings, @JsonInclude(JsonInclude.Include.NON_NULL) SplitMode splitMode, List<PersonShareLineResponse> shares) {

    public record InstallmentResponse(int number, int total, Money amount, String competence, InvoiceRefResponse invoice,
                                      boolean lateEntry, Money myAmount, Money thirdPartyAmount, List<PersonAmountResponse> byPerson) {
    }

    public static PurchasePreviewResponse of(PurchasePreviewView view) {
        return new PurchasePreviewResponse(view.installments().stream()
                .map(i -> new InstallmentResponse(i.number(), i.total(), i.amount(), i.competence().toString(),
                        InvoiceRefResponse.of(i.invoice()), i.lateEntry(), i.myAmount(), i.thirdPartyAmount(),
                        i.byPerson().stream().map(PersonAmountResponse::of).toList()))
                .toList(), view.total(), view.availableLimitAfter(), view.overLimit(),
                view.warnings().stream().map(Enum::name).toList(), view.splitMode(),
                view.shares().stream().map(PersonShareLineResponse::of).toList());
    }
}
