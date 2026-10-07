package com.moneylog.infra.card.controller;

import java.time.LocalDate;

import com.moneylog.infra.web.validation.DateInWindow;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Corpo de {@code POST /v1/cards/{id}/purchase-preview} (contrato: {@code PurchasePreviewRequest}). */
public record PurchasePreviewRequest(@NotNull @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,2})?$") String amount,
                                     @NotNull @Min(1) @Max(36) Integer installments, @NotNull @DateInWindow LocalDate date) {
}
