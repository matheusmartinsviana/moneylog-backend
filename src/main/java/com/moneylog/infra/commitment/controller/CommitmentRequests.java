package com.moneylog.infra.commitment.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.commitment.domain.CommitmentClauses;
import com.moneylog.core.commitment.domain.CommitmentKind;
import com.moneylog.core.commitment.domain.ItemShare;
import com.moneylog.core.commitment.domain.ScheduleItemType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.web.ProvenanceInput;
import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CommitmentRequests {

    public static final String AMOUNT_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";
    public static final String AMOUNT_OR_ZERO_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";

    private CommitmentRequests() {
    }

    public record ItemInput(@NotBlank @Size(max = 100) @NoControlChars String label, @NotNull ScheduleItemType type,
                            @DateInWindow LocalDate dueDate, @NotNull @Pattern(regexp = AMOUNT_PATTERN) String amount,
                            boolean paid, @DateInWindow LocalDate paidOn, @Valid @Size(max = 10) List<ShareInput> shares,
                            UUID coBuyerPersonId, @Valid ProvenanceInput provenance) {
    }

    public record ShareInput(UUID personId, @NotNull @Pattern(regexp = AMOUNT_PATTERN) String amount) {

        public ItemShare toDomain() {
            return new ItemShare(personId, Money.of(amount, CurrencyCode.BRL));
        }

        public static List<ItemShare> toDomain(List<ShareInput> inputs) {
            return inputs == null ? List.of() : inputs.stream().map(ShareInput::toDomain).toList();
        }
    }

    public record ClausesInput(@NotNull @Min(0) @Max(10000) Integer lateFeeBps, @NotNull @Min(0) @Max(10000) Integer monthlyInterestBps,
                               @NotNull @Min(0) @Max(10000) Integer terminationRetentionBps, @Size(max = 1000) String notes) {

        public CommitmentClauses toDomain() {
            return new CommitmentClauses(lateFeeBps, monthlyInterestBps, terminationRetentionBps, notes);
        }
    }

    public record SharesUpdate(@NotNull @NotEmpty @Valid @Size(max = 10) List<ShareInput> shares, UUID coBuyerPersonId, @NotNull Long version) {
    }

    public record ClausesUpdate(@NotNull @Valid ClausesInput clauses, @NotNull Long version) {
    }

    public record CommitmentInput(@NotNull CommitmentKind kind, @NotBlank @Size(max = 100) @NoControlChars String name,
                                  @Size(max = 100) @NoControlChars String counterparty, @Size(max = 1000) String notes,
                                  @Pattern(regexp = AMOUNT_PATTERN) String total, @Min(1) Integer installmentsTotal,
                                  @Min(0) Integer installmentsPaid, @Pattern(regexp = AMOUNT_OR_ZERO_PATTERN) String paidAmount,
                                  @DateInWindow LocalDate nextDueDate, @Valid @Size(max = 120) List<ItemInput> schedule,
                                  @Valid ClausesInput clauses) {
    }

    public record MarkPaidInput(@NotNull @DateInWindow LocalDate paidOn, @NotNull Long version) {
    }

    public record ProgressInput(@NotNull @Min(0) Integer installmentsPaid, @Pattern(regexp = AMOUNT_OR_ZERO_PATTERN) String paidAmount,
                                @DateInWindow LocalDate nextDueDate, @Size(max = 1000) String notes, @NotNull Long version,
                                @Size(max = 100) @NoControlChars String name, @Size(max = 100) @NoControlChars String counterparty) {
    }
}
