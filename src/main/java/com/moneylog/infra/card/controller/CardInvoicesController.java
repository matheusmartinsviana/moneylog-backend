package com.moneylog.infra.card.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.cards.usecase.GetFutureInstallmentsUseCase;
import com.moneylog.core.finance.app.cards.usecase.GetFutureInstallmentsUseCase.GetFutureInstallmentsQuery;
import com.moneylog.core.finance.app.cards.usecase.ListInvoicesUseCase;
import com.moneylog.core.finance.app.cards.usecase.ListInvoicesUseCase.ListInvoicesQuery;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.SliceResponse;

/** Faturas e parcelas futuras de um cartão (leituras; os números vêm todos da cadeia de faturas do backend). */
@RestController
@RequestMapping("/v1/cards/{id}")
public class CardInvoicesController {

    private final ListInvoicesUseCase list;
    private final GetFutureInstallmentsUseCase future;

    public CardInvoicesController(ListInvoicesUseCase list, GetFutureInstallmentsUseCase future) {
        this.list = list;
        this.future = future;
    }

    /** Por {@code closingDate}, ordem decrescente; sem {@code from}/{@code to}, a janela padrão de S-131. */
    @GetMapping("/invoices")
    public SliceResponse<InvoiceResponse> invoices(@CurrentUser UUID userId, @PathVariable UUID id,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   PageQuery page) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new RequestValidationException("from", "INVALID_RANGE", "'from' não pode ser depois de 'to'.");
        }
        return SliceResponse.of(list.handle(new ListInvoicesQuery(userId, id, from, to, page)), InvoiceResponse::of);
    }

    @GetMapping("/future-installments")
    public FutureInstallmentsResponse futureInstallments(@CurrentUser UUID userId, @PathVariable UUID id) {
        return FutureInstallmentsResponse.of(future.handle(new GetFutureInstallmentsQuery(userId, id)));
    }
}
