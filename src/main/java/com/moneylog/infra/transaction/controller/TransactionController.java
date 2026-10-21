package com.moneylog.infra.transaction.controller;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.constraints.Size;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.usecase.CancelInstallmentsUseCase;
import com.moneylog.core.finance.app.transactions.usecase.CancelInstallmentsUseCase.CancelInstallmentsCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCase;
import com.moneylog.core.finance.app.transactions.usecase.GetTransactionUseCase;
import com.moneylog.core.finance.app.transactions.usecase.GetTransactionUseCase.GetTransactionQuery;
import com.moneylog.core.finance.app.transactions.usecase.ListTransactionsUseCase;
import com.moneylog.core.finance.app.transactions.usecase.ListTransactionsUseCase.ListTransactionsQuery;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionSharesUseCase;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionUseCase;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionUseCase.UpdateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCase;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCase.VoidTransactionCommand;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.transaction.app.query.TransactionFilter;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.security.CurrentUser;

@RestController
@RequestMapping("/v1/transactions")
@Validated
public class TransactionController {

    private final CreateTransactionUseCase create;
    private final GetTransactionUseCase get;
    private final ListTransactionsUseCase list;
    private final UpdateTransactionUseCase update;
    private final VoidTransactionUseCase voidUseCase;
    private final CancelInstallmentsUseCase cancelInstallments;
    private final UpdateTransactionSharesUseCase updateShares;
    private final Validator validator;

    public TransactionController(CreateTransactionUseCase create, GetTransactionUseCase get, ListTransactionsUseCase list,
                                 UpdateTransactionUseCase update, VoidTransactionUseCase voidUseCase,
                                 CancelInstallmentsUseCase cancelInstallments, UpdateTransactionSharesUseCase updateShares,
                                 Validator validator) {
        this.cancelInstallments = cancelInstallments;
        this.updateShares = updateShares;
        this.validator = validator;
        this.create = create;
        this.get = get;
        this.list = list;
        this.update = update;
        this.voidUseCase = voidUseCase;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@CurrentUser UUID userId,
                                                      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                                      @Valid @RequestBody TransactionInput body) {
        if (body.version() != null && !(body instanceof TransactionInput.Unavailable)) {
            throw new RequestValidationException("version", "UNKNOWN_FIELD", "Campo desconhecido: 'version'.");
        }
        validateStrict(body);
        TransactionDetails created = create.handle(body.toCommand(userId), idempotencyKey);
        return ResponseEntity.created(URI.create("/v1/transactions/" + created.transaction().id()))
                .body(TransactionResponse.of(created));
    }

    private void validateStrict(TransactionInput body) {
        if (body instanceof TransactionInput.StrictValidated) {
            var violations = validator.validate(body, TransactionInput.Strict.class);
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException(violations);
            }
        }
    }

    @GetMapping
    public TransactionListResponse list(@CurrentUser UUID userId,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(required = false) TransactionType type,
                                        @RequestParam(required = false) UUID accountId, @RequestParam(required = false) UUID categoryId,
                                        @RequestParam(required = false) PaymentMethod method,
                                        @RequestParam(required = false) TransactionStatus status,
                                        @RequestParam(required = false) TransactionSource source,
                                        @RequestParam(required = false) @Size(max = TransactionFilter.Q_MAX_LENGTH) @NoControlChars String q,
                                        PageQuery page) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new RequestValidationException("from", "INVALID_RANGE", "'from' não pode ser depois de 'to'.");
        }
        TransactionFilter filter = new TransactionFilter(from, to, type, accountId, categoryId, method, status, q, source);
        return TransactionListResponse.of(list.handle(new ListTransactionsQuery(userId, filter, page)));
    }

    @PutMapping("/{id}")
    public TransactionResponse update(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody TransactionInput body) {
        boolean versionless = body instanceof TransactionInput.Unavailable || body instanceof TransactionInput.NotEditable;
        if (body.version() == null && !versionless) {
            throw new RequestValidationException("version", "REQUIRED", "'version' é obrigatória na edição.");
        }
        if (body instanceof TransactionInput.CardPurchase) {
            validateStrict(body);
        }
        long version = body.version() == null ? 0L : body.version();
        return TransactionResponse.of(update.handle(new UpdateTransactionCommand(id, version, body.toCommand(userId))));
    }

    @PutMapping("/{id}/shares")
    public TransactionResponse updateShares(@CurrentUser UUID userId, @PathVariable UUID id,
                                            @Valid @RequestBody TransactionSharesInput body) {
        return TransactionResponse.of(updateShares.handle(body.toCommand(userId, id)));
    }

    @PostMapping("/{id}/void")
    public TransactionResponse voidTransaction(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody VoidRequest body) {
        return TransactionResponse.of(voidUseCase.handle(new VoidTransactionCommand(userId, id, body.reason())));
    }

    @PostMapping("/{id}/cancel-installments")
    public TransactionResponse cancelInstallments(@CurrentUser UUID userId, @PathVariable UUID id,
                                                  @Valid @RequestBody VoidRequest body) {
        return TransactionResponse.of(cancelInstallments.handle(new CancelInstallmentsCommand(userId, id, body.reason())));
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return TransactionResponse.of(get.handle(new GetTransactionQuery(userId, id)));
    }
}
