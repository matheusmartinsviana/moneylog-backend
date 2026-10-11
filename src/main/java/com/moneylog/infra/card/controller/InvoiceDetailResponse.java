package com.moneylog.infra.card.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.finance.app.cards.InvoiceDetails;
import com.moneylog.core.sk.money.Money;

public record InvoiceDetailResponse(UUID id, UUID cardId, String referenceMonth, LocalDate periodStart, LocalDate closingDate,
                                    LocalDate dueDate, InvoiceStatus status, Money total, Money carryIn, Money paid,
                                    Money outstanding, Money creditCarry, boolean hasLateEntries, List<ItemResponse> items,
                                    List<PaymentResponse> payments) {

    public record ItemResponse(String kind, UUID transactionId, String description, Integer number, Integer totalInstallments,
                               Money amount, LocalDate date, boolean lateEntry) {
        public static ItemResponse of(InvoiceDetails.Item item) {
            return new ItemResponse(item.kind().name(), item.transactionId(), item.description(), item.number(),
                    item.totalInstallments(), item.amount(), item.date(), item.lateEntry());
        }
    }

    public record PaymentResponse(UUID transactionId, UUID accountId, Money amount, LocalDate date) {
        public static PaymentResponse of(InvoiceDetails.Payment payment) {
            return new PaymentResponse(payment.transactionId(), payment.accountId(), payment.amount(), payment.date());
        }
    }

    public static InvoiceDetailResponse of(InvoiceDetails details) {
        var view = details.invoice();
        return new InvoiceDetailResponse(view.id(), view.cardId(), view.referenceMonth().toString(), view.periodStart(),
                view.closingDate(), view.dueDate(), view.status(), view.total(), view.carryIn(), view.paid(), view.outstanding(),
                view.creditCarry(), view.hasLateEntries(), details.items().stream().map(ItemResponse::of).toList(),
                details.payments().stream().map(PaymentResponse::of).toList());
    }
}
