package com.moneylog.infra.asset.controller;

import java.time.LocalDate;

import com.moneylog.core.asset.app.AssetSpec;
import com.moneylog.core.asset.domain.AssetAvailability;
import com.moneylog.core.asset.domain.AssetKind;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.web.ProvenanceInput;
import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AssetRequests {

    public static final String AMOUNT_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";

    private AssetRequests() {
    }

    public record AssetInput(@NotNull AssetKind kind, @NotNull @Size(min = 1, max = 100) @NoControlChars String name,
                             @Size(max = 100) @NoControlChars String employer, @NotNull @Pattern(regexp = AMOUNT_PATTERN) String balance,
                             @NotNull @DateInWindow LocalDate balanceDate, @Pattern(regexp = AMOUNT_PATTERN) String monthlyDeposit,
                             @NotNull AssetAvailability availability, Boolean countsAsReserve,
                             @Valid ProvenanceInput provenance) {

        AssetSpec toSpec() {
            return new AssetSpec(kind, name, employer, Money.of(balance, CurrencyCode.BRL), balanceDate, money(monthlyDeposit), availability,
                    Boolean.TRUE.equals(countsAsReserve), ProvenanceInput.toDomain(provenance));
        }
    }

    public record AssetUpdate(@NotNull AssetKind kind, @NotNull @Size(min = 1, max = 100) @NoControlChars String name,
                              @Size(max = 100) @NoControlChars String employer, @Pattern(regexp = AMOUNT_PATTERN) String monthlyDeposit,
                              @NotNull AssetAvailability availability, Boolean countsAsReserve, @NotNull Long version,
                              @Valid ProvenanceInput provenance) {

        AssetSpec toSpec() {
            return new AssetSpec(kind, name, employer, null, null, money(monthlyDeposit), availability, Boolean.TRUE.equals(countsAsReserve),
                    ProvenanceInput.toDomain(provenance));
        }
    }

    public record AssetBalanceInput(@NotNull @Pattern(regexp = AMOUNT_PATTERN) String balance, @NotNull @DateInWindow LocalDate date) {

        Money toMoney() {
            return Money.of(balance, CurrencyCode.BRL);
        }
    }

    private static Money money(String value) {
        return value == null ? null : Money.of(value, CurrencyCode.BRL);
    }
}
