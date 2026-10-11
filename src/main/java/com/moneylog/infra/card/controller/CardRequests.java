package com.moneylog.infra.card.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.constraints.NotNull;

public final class CardRequests {

    private CardRequests() {
    }

    public record CardInput(@NotNull @NoControlChars String name, @NoControlChars String institution, Money limit,
                            @NotNull Integer closingDay, @NotNull Integer dueDay, UUID defaultPaymentAccountId) {
    }

    public record CardUpdate(@NotNull @NoControlChars String name, @NoControlChars String institution, Money limit,
                             @NotNull Integer closingDay, @NotNull Integer dueDay, UUID defaultPaymentAccountId,
                             LocalDate confirmedEffectiveFrom, @NotNull Long version) {
    }
}
