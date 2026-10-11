package com.moneylog.infra.card.controller;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.cards.usecase.CreateHistoricalInvoiceUseCase;
import com.moneylog.core.finance.app.cards.usecase.CreateHistoricalInvoiceUseCase.CreateHistoricalInvoiceCommand;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.transaction.controller.TransactionResponse;
import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/v1/cards/{id}/historical-invoices")
public class HistoricalInvoiceController {

    public record HistoricalInvoiceRequest(@NotNull @DateInWindow LocalDate closingDate,
                                           @NotNull @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,2})?$") String amount,
                                           @NotBlank @Size(max = 200) @NoControlChars String description) {
    }

    private final CreateHistoricalInvoiceUseCase create;

    public HistoricalInvoiceController(CreateHistoricalInvoiceUseCase create) {
        this.create = create;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@CurrentUser UUID userId, @PathVariable UUID id,
                                                      @Valid @RequestBody HistoricalInvoiceRequest body) {
        TransactionDetails created = create.handle(new CreateHistoricalInvoiceCommand(userId, id, body.closingDate(),
                Money.of(body.amount(), CurrencyCode.BRL), body.description()));
        return ResponseEntity.created(URI.create("/v1/transactions/" + created.transaction().id())).body(TransactionResponse.of(created));
    }
}
