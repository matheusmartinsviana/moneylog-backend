package com.moneylog.infra.card.controller;

import java.util.UUID;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.moneylog.core.finance.app.cards.usecase.ChangeInvoiceDocumentAmountUseCase;
import com.moneylog.core.finance.app.cards.usecase.ChangeInvoiceDocumentAmountUseCase.ChangeInvoiceDocumentAmountCommand;
import com.moneylog.core.finance.app.cards.usecase.GetInvoiceUseCase;
import com.moneylog.core.finance.app.cards.usecase.GetInvoiceUseCase.GetInvoiceQuery;
import com.moneylog.core.finance.app.cards.usecase.PayInvoiceUseCase;
import com.moneylog.core.finance.app.cards.usecase.PayInvoiceUseCase.PayInvoiceCommand;
import com.moneylog.core.finance.app.cards.usecase.PreviewInvoicePaymentUseCase;
import com.moneylog.core.finance.app.cards.usecase.PreviewInvoicePaymentUseCase.PreviewInvoicePaymentQuery;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.security.CurrentUser;

@RestController
@RequestMapping("/v1/invoices")
@Validated
public class InvoiceController {

    private final GetInvoiceUseCase get;
    private final PreviewInvoicePaymentUseCase previewPayment;
    private final PayInvoiceUseCase pay;
    private final ChangeInvoiceDocumentAmountUseCase changeDocumentAmount;

    public InvoiceController(GetInvoiceUseCase get, PreviewInvoicePaymentUseCase previewPayment, PayInvoiceUseCase pay,
                             ChangeInvoiceDocumentAmountUseCase changeDocumentAmount) {
        this.changeDocumentAmount = changeDocumentAmount;
        this.get = get;
        this.previewPayment = previewPayment;
        this.pay = pay;
    }

    @GetMapping("/{id}")
    public InvoiceDetailResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return InvoiceDetailResponse.of(get.handle(new GetInvoiceQuery(userId, id)));
    }

    @PutMapping("/{id}/document-amount")
    public InvoiceDetailResponse changeDocumentAmount(@CurrentUser UUID userId, @PathVariable UUID id,
                                                      @Valid @RequestBody InvoiceDocumentAmountInput body) {
        Money amount = body.documentAmount() == null ? null : Money.of(body.documentAmount(), CurrencyCode.BRL);
        return InvoiceDetailResponse.of(changeDocumentAmount.handle(new ChangeInvoiceDocumentAmountCommand(userId, id, amount, body.version())));
    }

    @GetMapping("/{id}/payment-preview")
    public PaymentPreviewResponse previewPayment(@CurrentUser UUID userId, @PathVariable UUID id,
                                                 @RequestParam @NotNull @Pattern(regexp = InvoicePaymentInput.AMOUNT_PATTERN) String amount) {
        return PaymentPreviewResponse.of(previewPayment.handle(new PreviewInvoicePaymentQuery(userId, id,
                Money.of(amount, CurrencyCode.BRL))));
    }

    @PostMapping("/{id}/payments")
    public InvoiceDetailResponse pay(@CurrentUser UUID userId, @PathVariable UUID id,
                                     @RequestHeader("Idempotency-Key") UUID idempotencyKey, @Valid @RequestBody InvoicePaymentInput body) {
        return InvoiceDetailResponse.of(pay.handle(new PayInvoiceCommand(userId, id, body.accountId(),
                Money.of(body.amount(), CurrencyCode.BRL), body.date()), idempotencyKey));
    }
}
