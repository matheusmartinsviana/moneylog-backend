package com.moneylog.infra.card.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OngoingInstallmentsRequest(@NotBlank @Size(max = 200) @NoControlChars String description,
                                         @NotNull @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,2})?$") String installmentAmount,
                                         @NotNull @Min(2) @Max(36) Integer totalInstallments,
                                         @NotNull @Min(1) @Max(36) Integer remainingInstallments,
                                         @DateInWindow LocalDate firstInvoiceDate, UUID categoryId) {
}
