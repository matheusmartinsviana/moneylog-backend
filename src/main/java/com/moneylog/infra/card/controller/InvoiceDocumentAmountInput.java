package com.moneylog.infra.card.controller;

import jakarta.validation.constraints.Pattern;

public record InvoiceDocumentAmountInput(@Pattern(regexp = InvoiceDocumentAmountInput.AMOUNT_PATTERN) String documentAmount, Long version) {

    public static final String AMOUNT_PATTERN = "^-?\\d{1,13}(\\.\\d{1,2})?$";
}
