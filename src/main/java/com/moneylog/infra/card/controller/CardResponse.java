package com.moneylog.infra.card.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.sk.money.Money;

/** Saída de cartão (contrato: {@code Card}). Todos os números vêm calculados do backend (S-129). */
public record CardResponse(UUID id, String name, String institution, Money limit, Money availableLimit, boolean overLimit,
                           Money creditBalance, BigDecimal usedRatio, int closingDay, int dueDay, UUID defaultPaymentAccountId,
                           CardStatus status, InvoiceResponse currentInvoice, Money totalCommitted, Money committedFuture,
                           boolean archivable, boolean closingConfirmed, long version, Instant createdAt, Instant updatedAt) {

    public static CardResponse of(CardDetails details) {
        CreditCard card = details.card();
        return new CardResponse(card.id(), card.name(), card.institution(), card.limit(), details.limit().available(),
                details.limit().overLimit(), details.creditBalance(), details.usedRatio(), card.closingDay(), card.dueDay(),
                card.defaultPaymentAccountId(), card.status(), InvoiceResponse.of(details.currentInvoice()),
                details.totalCommitted(), details.committedFuture(), details.archivable(), card.closingDayConfirmed(), card.version(), details.createdAt(),
                details.updatedAt());
    }
}
