package com.moneylog.infra.card.controller;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.moneylog.infra.web.validation.DateInWindow;

/** Corpo de {@code POST /v1/invoices/{id}/payments} (contrato: {@code InvoicePaymentInput}). */
public record InvoicePaymentInput(@NotNull UUID accountId, @NotNull @Pattern(regexp = InvoicePaymentInput.AMOUNT_PATTERN) String amount,
                                  @NotNull @DateInWindow LocalDate date) {

    public static final String AMOUNT_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";
}
