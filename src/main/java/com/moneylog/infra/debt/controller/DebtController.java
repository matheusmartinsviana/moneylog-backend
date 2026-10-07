package com.moneylog.infra.debt.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
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

import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.finance.app.debts.usecase.CancelDebtUseCase;
import com.moneylog.core.finance.app.debts.usecase.CancelDebtUseCase.CancelDebtCommand;
import com.moneylog.core.finance.app.debts.usecase.CreateDebtUseCase;
import com.moneylog.core.finance.app.debts.usecase.CreateDebtUseCase.CreateDebtCommand;
import com.moneylog.core.finance.app.debts.usecase.UpdateDebtUseCase;
import com.moneylog.core.finance.app.debts.usecase.UpdateDebtUseCase.UpdateDebtCommand;
import com.moneylog.core.finance.app.debts.usecase.VoidDebtRecordUseCase;
import com.moneylog.core.finance.app.debts.usecase.VoidDebtRecordUseCase.VoidDebtRecordCommand;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.debt.controller.DebtRequests.DebtInput;
import com.moneylog.infra.debt.controller.DebtRequests.DebtUpdate;
import com.moneylog.infra.debt.controller.DebtRequests.ReasonInput;
import com.moneylog.core.debt.app.gateways.DebtGateway.DebtFilter;
import com.moneylog.core.debt.app.usecase.GetDebtUseCase;
import com.moneylog.core.debt.app.usecase.GetDebtUseCase.GetDebtQuery;
import com.moneylog.core.debt.app.usecase.ListDebtsUseCase;
import com.moneylog.core.debt.app.usecase.ListDebtsUseCase.ListDebtsQuery;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.SliceResponse;

/**
 * Dívidas (D1 §2.6): leitura, criação sem movimentação, edição, perdão e estorno do registro. As dívidas com movimentação
 * nascem em {@code POST /v1/transactions} e o estorno delas é o da movimentação de origem.
 */
@RestController
@RequestMapping("/v1/debts")
public class DebtController {

    private final GetDebtUseCase get;
    private final ListDebtsUseCase list;
    private final CreateDebtUseCase create;
    private final UpdateDebtUseCase update;
    private final CancelDebtUseCase cancel;
    private final VoidDebtRecordUseCase voidRecord;

    public DebtController(GetDebtUseCase get, ListDebtsUseCase list, CreateDebtUseCase create, UpdateDebtUseCase update,
                          CancelDebtUseCase cancel, VoidDebtRecordUseCase voidRecord) {
        this.get = get;
        this.list = list;
        this.create = create;
        this.update = update;
        this.cancel = cancel;
        this.voidRecord = voidRecord;
    }

    @PostMapping
    public ResponseEntity<DebtResponse> create(@CurrentUser UUID userId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                               @Valid @RequestBody DebtInput body) {
        DebtView created = create.handle(new CreateDebtCommand(userId, body.personId(), body.direction(),
                Money.of(body.principal(), CurrencyCode.BRL), body.date(), body.expectedDate(), body.description(), body.categoryId(), idempotencyKey,
                body.creditorName()));
        return ResponseEntity.created(URI.create("/v1/debts/" + created.debt().id())).body(DebtResponse.of(created));
    }

    @PutMapping("/{id}")
    public DebtResponse update(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody DebtUpdate body) {
        return DebtResponse.of(update.handle(new UpdateDebtCommand(userId, id, body.expectedDate(), body.description(), body.version())));
    }

    /** Perdão (S-19): zera o saldo restante contra {@code EQUITY}; não se desfaz. */
    @PostMapping("/{id}/cancel")
    public DebtResponse cancel(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody ReasonInput body) {
        return DebtResponse.of(cancel.handle(new CancelDebtCommand(userId, id, body.reason())));
    }

    /** Estorno do registro de uma dívida sem movimentação (sem quitações ativas). */
    @PostMapping("/{id}/void")
    public DebtResponse voidRecord(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody ReasonInput body) {
        return DebtResponse.of(voidRecord.handle(new VoidDebtRecordCommand(userId, id, body.reason())));
    }

    @GetMapping
    public SliceResponse<DebtResponse> list(@CurrentUser UUID userId, @RequestParam(required = false) DebtDirection direction,
                                            @RequestParam(required = false) DebtStatus status,
                                            @RequestParam(required = false) UUID personId,
                                            @RequestParam(required = false) Boolean overdue, PageQuery page) {
        return SliceResponse.of(list.handle(new ListDebtsQuery(userId, new DebtFilter(direction, status, personId, overdue), page)),
                DebtResponse::of);
    }

    @GetMapping("/{id}")
    public DebtResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return DebtResponse.of(get.handle(new GetDebtQuery(userId, id)));
    }
}
