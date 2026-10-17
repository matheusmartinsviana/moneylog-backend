package com.moneylog.infra.card.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.card.domain.CardProfile;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class CardRequests {

    private CardRequests() {
    }

    public record CardInput(@NotNull @NoControlChars String name, @NoControlChars String institution, Money limit,
                            @NotNull Integer closingDay, @NotNull Integer dueDay, UUID defaultPaymentAccountId, Boolean autoDebit,
                            @Size(max = 40) @NoControlChars String brand, Integer bestPurchaseDay,
                            List<String> lastDigits) {

        public CardProfile profile() {
            return new CardProfile(autoDebit, brand, bestPurchaseDay, lastDigits);
        }
    }

    public record CardUpdate(@NotNull @NoControlChars String name, @NoControlChars String institution, Money limit,
                             @NotNull Integer closingDay, @NotNull Integer dueDay, UUID defaultPaymentAccountId,
                             LocalDate confirmedEffectiveFrom, @NotNull Long version, Boolean autoDebit,
                             @Size(max = 40) @NoControlChars String brand, Integer bestPurchaseDay,
                             List<String> lastDigits) {

        public CardProfile profile() {
            return new CardProfile(autoDebit, brand, bestPurchaseDay, lastDigits);
        }
    }
}
