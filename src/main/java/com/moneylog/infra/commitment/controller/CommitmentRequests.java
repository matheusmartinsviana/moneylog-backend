package com.moneylog.infra.commitment.controller;

import java.time.LocalDate;
import java.util.List;

import com.moneylog.core.commitment.domain.CommitmentKind;
import com.moneylog.core.commitment.domain.ScheduleItemType;
import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Corpos de entrada de {@code /v1/commitments}. */
public final class CommitmentRequests {

    public static final String AMOUNT_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";
    /** Valor pago pode ser 0. */
    public static final String AMOUNT_OR_ZERO_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";

    private CommitmentRequests() {
    }

    public record ItemInput(@NotBlank @Size(max = 100) @NoControlChars String label, @NotNull ScheduleItemType type,
                            @DateInWindow LocalDate dueDate, @NotNull @Pattern(regexp = AMOUNT_PATTERN) String amount,
                            boolean paid, @DateInWindow LocalDate paidOn) {
    }

    public record CommitmentInput(@NotNull CommitmentKind kind, @NotBlank @Size(max = 100) @NoControlChars String name,
                                  @Size(max = 100) @NoControlChars String counterparty, @Size(max = 1000) String notes,
                                  @Pattern(regexp = AMOUNT_PATTERN) String total, @Min(1) Integer installmentsTotal,
                                  @Min(0) Integer installmentsPaid, @Pattern(regexp = AMOUNT_OR_ZERO_PATTERN) String paidAmount,
                                  @DateInWindow LocalDate nextDueDate, @Valid @Size(max = 120) List<ItemInput> schedule) {
    }

    public record MarkPaidInput(@NotNull @DateInWindow LocalDate paidOn, @NotNull Long version) {
    }

    public record ProgressInput(@NotNull @Min(0) Integer installmentsPaid, @Pattern(regexp = AMOUNT_OR_ZERO_PATTERN) String paidAmount,
                                @DateInWindow LocalDate nextDueDate, @Size(max = 1000) String notes, @NotNull Long version,
                                @Size(max = 100) @NoControlChars String name, @Size(max = 100) @NoControlChars String counterparty) {
    }
}
