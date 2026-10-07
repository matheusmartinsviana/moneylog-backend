package com.moneylog.infra.account.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.finance.app.accounts.usecase.ArchiveAccountUseCase;
import com.moneylog.core.finance.app.accounts.usecase.ArchiveAccountUseCase.ArchiveAccountCommand;
import com.moneylog.core.finance.app.accounts.usecase.CreateAccountUseCase;
import com.moneylog.core.finance.app.accounts.usecase.CreateAccountUseCase.CreateAccountCommand;
import com.moneylog.core.finance.app.accounts.usecase.GetAccountUseCase;
import com.moneylog.core.finance.app.accounts.usecase.GetAccountUseCase.GetAccountQuery;
import com.moneylog.core.finance.app.accounts.usecase.ListAccountsUseCase;
import com.moneylog.core.finance.app.accounts.usecase.ListAccountsUseCase.ListAccountsQuery;
import com.moneylog.core.finance.app.accounts.usecase.UnarchiveAccountUseCase;
import com.moneylog.core.finance.app.accounts.usecase.UnarchiveAccountUseCase.UnarchiveAccountCommand;
import com.moneylog.core.finance.app.accounts.usecase.UpdateAccountUseCase;
import com.moneylog.core.finance.app.accounts.usecase.UpdateAccountUseCase.UpdateAccountCommand;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.account.controller.AccountRequests.AccountInput;
import com.moneylog.infra.account.controller.AccountRequests.AccountUpdate;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.SliceResponse;

/** Contas (D1 §2.2): CRUD com saldo inicial como lançamento, arquivar e desarquivar. */
@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

    private final CreateAccountUseCase create;
    private final UpdateAccountUseCase update;
    private final ArchiveAccountUseCase archive;
    private final UnarchiveAccountUseCase unarchive;
    private final GetAccountUseCase get;
    private final ListAccountsUseCase list;

    public AccountController(CreateAccountUseCase create, UpdateAccountUseCase update, ArchiveAccountUseCase archive,
                             UnarchiveAccountUseCase unarchive, GetAccountUseCase get, ListAccountsUseCase list) {
        this.create = create;
        this.update = update;
        this.archive = archive;
        this.unarchive = unarchive;
        this.get = get;
        this.list = list;
    }

    @GetMapping
    public SliceResponse<AccountResponse> list(@CurrentUser UUID userId, @RequestParam(required = false) AccountStatus status,
                                               PageQuery page) {
        return SliceResponse.of(list.handle(new ListAccountsQuery(userId, status, page)), AccountResponse::of);
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@CurrentUser UUID userId, @Valid @RequestBody AccountInput body) {
        AccountDetails created = create.handle(new CreateAccountCommand(userId, body.name(), body.type(), body.institution(),
                body.openingBalance(), body.openingDate()));
        return ResponseEntity.created(URI.create("/v1/accounts/" + created.account().id())).body(AccountResponse.of(created));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return AccountResponse.of(get.handle(new GetAccountQuery(userId, id)));
    }

    @PutMapping("/{id}")
    public AccountResponse update(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody AccountUpdate body) {
        return AccountResponse.of(update.handle(new UpdateAccountCommand(userId, id, body.name(), body.institution(), body.type(),
                body.openingBalance(), body.openingDate(), body.version())));
    }

    @PostMapping("/{id}/archive")
    public AccountResponse archive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return AccountResponse.of(archive.handle(new ArchiveAccountCommand(userId, id)));
    }

    @PostMapping("/{id}/unarchive")
    public AccountResponse unarchive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return AccountResponse.of(unarchive.handle(new UnarchiveAccountCommand(userId, id)));
    }
}
