package com.moneylog.infra.card.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.moneylog.core.finance.app.cards.usecase.CreateOngoingInstallmentsUseCase;
import com.moneylog.core.finance.app.cards.usecase.CreateOngoingInstallmentsUseCase.CreateOngoingInstallmentsCommand;
import com.moneylog.core.finance.app.cards.usecase.PreviewOngoingInstallmentsUseCase;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.transaction.controller.TransactionResponse;

/**
 * Compra parcelada em andamento (ADR-013): só as parcelas que faltam, sem consumo retroativo. {@code 201} devolve a compra
 * ({@code CARD_PURCHASE}, origem {@code ONGOING}) com {@code Location} na movimentação. Sem idempotência no MVP: repetir cria outra compra.
 */
@RestController
@RequestMapping("/v1/cards/{id}/ongoing-installments")
public class OngoingInstallmentsController {

    private final CreateOngoingInstallmentsUseCase create;
    private final PreviewOngoingInstallmentsUseCase preview;

    public OngoingInstallmentsController(CreateOngoingInstallmentsUseCase create, PreviewOngoingInstallmentsUseCase preview) {
        this.create = create;
        this.preview = preview;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@CurrentUser UUID userId, @PathVariable UUID id,
                                                      @Valid @RequestBody OngoingInstallmentsRequest body) {
        TransactionDetails created = create.handle(command(userId, id, body));
        return ResponseEntity.created(URI.create("/v1/transactions/" + created.transaction().id())).body(TransactionResponse.of(created));
    }

    /** O que a criação gravaria, calculado pelo mesmo caminho e sem gravar. */
    @PostMapping("/preview")
    public OngoingPreviewResponse preview(@CurrentUser UUID userId, @PathVariable UUID id,
                                          @Valid @RequestBody OngoingInstallmentsRequest body) {
        return OngoingPreviewResponse.of(preview.handle(command(userId, id, body)));
    }

    private static CreateOngoingInstallmentsCommand command(UUID userId, UUID cardId, OngoingInstallmentsRequest body) {
        return new CreateOngoingInstallmentsCommand(userId, cardId, body.description(),
                Money.of(body.installmentAmount(), CurrencyCode.BRL), body.totalInstallments(), body.remainingInstallments(),
                body.firstInvoiceDate(), body.categoryId());
    }
}
