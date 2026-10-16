package com.moneylog.infra.card.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.finance.app.cards.usecase.CreateCardCashOutUseCase;
import com.moneylog.core.finance.app.cards.usecase.CreateCardCashOutUseCase.CreateCardCashOutCommand;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.debt.controller.DebtResponse;
import com.moneylog.infra.security.CurrentUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/card-cash-outs")
public class CardCashOutController {

    private final CreateCardCashOutUseCase create;

    public CardCashOutController(CreateCardCashOutUseCase create) {
        this.create = create;
    }

    @PostMapping
    public ResponseEntity<DebtResponse> create(@CurrentUser UUID userId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                               @Valid @RequestBody CardCashOutRequest body) {
        DebtView created = create.handle(new CreateCardCashOutCommand(userId, body.cardId(), body.accountId(),
                Money.of(body.principal(), CurrencyCode.BRL), body.installments(),
                body.totalWithInterest() == null ? null : Money.of(body.totalWithInterest(), CurrencyCode.BRL), body.rateBps(),
                body.firstInvoiceDate(), body.beneficiaryPersonId(), body.interestCategoryId(), body.channel(), idempotencyKey));
        return ResponseEntity.created(URI.create("/v1/debts/" + created.debt().id())).body(DebtResponse.of(created));
    }
}
