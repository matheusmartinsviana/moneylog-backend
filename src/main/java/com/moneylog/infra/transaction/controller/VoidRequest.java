package com.moneylog.infra.transaction.controller;

import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corpo do {@code POST /v1/transactions/{id}/void} (contrato: {@code VoidRequest}). */
public record VoidRequest(@NotBlank @Size(max = 500) @NoControlChars String reason) {
}
