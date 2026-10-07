package com.moneylog.infra.card.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.constraints.NotNull;

/**
 * Corpos de entrada de {@code /v1/cards} (contrato: {@code CardInput} e {@code CardUpdate}). Nome, instituição, limite e
 * dias não têm limite no Bean Validation: o domínio decide e responde {@code 422} com o {@code code} da regra
 * ({@code CARD_NAME_REQUIRED}, {@code CARD_NAME_TOO_LONG}, {@code CARD_INVALID_BILLING_DAY}, {@code CARD_INVALID_LIMIT}...).
 */
public final class CardRequests {

    private CardRequests() {
    }

    public record CardInput(@NotNull @NoControlChars String name, @NoControlChars String institution, @NotNull Money limit,
                            @NotNull Integer closingDay, @NotNull Integer dueDay, UUID defaultPaymentAccountId) {
    }

    public record CardUpdate(@NotNull @NoControlChars String name, @NoControlChars String institution, @NotNull Money limit,
                             @NotNull Integer closingDay, @NotNull Integer dueDay, UUID defaultPaymentAccountId,
                             LocalDate confirmedEffectiveFrom, @NotNull Long version) {
    }
}
