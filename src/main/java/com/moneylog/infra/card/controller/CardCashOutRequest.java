package com.moneylog.infra.card.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CardCashOutRequest(@NotNull UUID cardId, UUID accountId,
                                 @NotNull @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,2})?$") String principal,
                                 @NotNull @Min(1) @Max(36) Integer installments,
                                 @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,2})?$") String totalWithInterest,
                                 @Min(0) @Max(10000) Integer rateBps, @NotNull @DateInWindow LocalDate firstInvoiceDate,
                                 UUID beneficiaryPersonId, UUID interestCategoryId,
                                 @Size(max = 60) @NoControlChars String channel) {
}
