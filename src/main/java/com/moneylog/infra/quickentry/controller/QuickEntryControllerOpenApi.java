package com.moneylog.infra.quickentry.controller;

import org.springframework.http.ResponseEntity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

import com.moneylog.infra.security.QuickTokenPrincipal;

@Tag(name = "QuickEntry")
public interface QuickEntryControllerOpenApi {

    @Operation(operationId = "createQuickEntry", summary = "Registra um gasto ou uma entrada pelo token do Atalho")
    ResponseEntity<QuickEntryResponse> create(QuickTokenPrincipal principal, String idempotencyKey, QuickEntryRequest body,
                                              HttpServletRequest request);
}
