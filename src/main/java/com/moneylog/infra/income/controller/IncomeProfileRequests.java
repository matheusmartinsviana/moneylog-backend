package com.moneylog.infra.income.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.income.app.IncomeProfileSpec;
import com.moneylog.core.income.domain.DeductionKind;
import com.moneylog.core.income.domain.PayrollAdvance;
import com.moneylog.core.income.domain.PayrollDeduction;
import com.moneylog.core.income.domain.ThirteenthSettings;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.web.ProvenanceInput;
import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class IncomeProfileRequests {

    public static final String AMOUNT_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";

    private IncomeProfileRequests() {
    }

    public record AdvanceInput(@NotNull Integer dayOfMonth, @NotNull Integer percentBps) {
        PayrollAdvance toDomain() {
            return new PayrollAdvance(dayOfMonth, percentBps);
        }
    }

    public record ThirteenthInput(@NotNull Boolean enabled, @NotNull Integer firstInstallmentMonth) {
        ThirteenthSettings toDomain() {
            return new ThirteenthSettings(enabled, firstInstallmentMonth);
        }
    }

    public record DeductionInput(@NotNull DeductionKind kind, @NotNull @Size(max = 200) @NoControlChars String label,
                                 @Pattern(regexp = AMOUNT_PATTERN) String amount, Integer percentBps,
                                 @DateInWindow LocalDate startsOn, @DateInWindow LocalDate endsOn, UUID debtId,
                                 @Pattern(regexp = AMOUNT_PATTERN) String interestAmount) {
        PayrollDeduction toDomain() {
            return new PayrollDeduction(kind, label, amount == null ? null : Money.of(amount, CurrencyCode.BRL), percentBps, startsOn,
                    endsOn, debtId, interestAmount == null ? null : Money.of(interestAmount, CurrencyCode.BRL));
        }
    }

    public record IncomeProfileInput(@NotNull @Size(max = 200) @NoControlChars String source,
                                     @NotNull @Pattern(regexp = AMOUNT_PATTERN) String gross, @NotNull Integer dependents,
                                     @Valid AdvanceInput advance, @NotNull Integer balanceDayOfMonth,
                                     @NotNull @Size(max = 100) List<@NotNull @Valid DeductionInput> deductions,
                                     @NotNull @Valid ThirteenthInput thirteenth, @Valid ProvenanceInput provenance) {
        IncomeProfileSpec toSpec() {
            return spec(source, gross, dependents, advance, balanceDayOfMonth, deductions, thirteenth, provenance);
        }
    }

    public record IncomeProfileUpdate(@NotNull @Size(max = 200) @NoControlChars String source,
                                      @NotNull @Pattern(regexp = AMOUNT_PATTERN) String gross, @NotNull Integer dependents,
                                      @Valid AdvanceInput advance, @NotNull Integer balanceDayOfMonth,
                                      @NotNull @Size(max = 100) List<@NotNull @Valid DeductionInput> deductions,
                                      @NotNull @Valid ThirteenthInput thirteenth, @NotNull Long version,
                                      @Valid ProvenanceInput provenance) {
        IncomeProfileSpec toSpec() {
            return spec(source, gross, dependents, advance, balanceDayOfMonth, deductions, thirteenth, provenance);
        }
    }

    private static IncomeProfileSpec spec(String source, String gross, int dependents, AdvanceInput advance, int balanceDayOfMonth,
                                          List<DeductionInput> deductions, ThirteenthInput thirteenth,
                                          ProvenanceInput provenance) {
        return new IncomeProfileSpec(source, Money.of(gross, CurrencyCode.BRL), dependents,
                advance == null ? null : advance.toDomain(), balanceDayOfMonth,
                deductions.stream().map(DeductionInput::toDomain).toList(), thirteenth.toDomain(),
                ProvenanceInput.toDomain(provenance));
    }
}
