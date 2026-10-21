package com.moneylog.infra.quickentry.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

import com.moneylog.infra.quickentry.controller.QuickEntryTokenRequests.QuickEntryTokenInput;
import com.moneylog.infra.quickentry.controller.QuickEntryTokenRequests.QuickEntryTokenRename;

@Tag(name = "QuickEntry")
public interface QuickEntryTokenControllerOpenApi {

    @Operation(operationId = "createQuickEntryToken", summary = "Gera um token de lançamento rápido, vinculado a uma conta")
    ResponseEntity<QuickEntryTokenCreatedResponse> create(UUID userId, QuickEntryTokenInput body, HttpServletRequest request);

    @Operation(operationId = "listQuickEntryTokens", summary = "Lista os tokens do usuário (lista curta, sem paginação)")
    ResponseEntity<List<QuickEntryTokenResponse>> list(UUID userId);

    @Operation(operationId = "renameQuickEntryToken", summary = "Renomeia um token")
    ResponseEntity<QuickEntryTokenResponse> rename(UUID userId, UUID id, QuickEntryTokenRename body);

    @Operation(operationId = "revokeQuickEntryToken", summary = "Revoga um token")
    ResponseEntity<QuickEntryTokenResponse> revoke(UUID userId, UUID id, HttpServletRequest request);
}
