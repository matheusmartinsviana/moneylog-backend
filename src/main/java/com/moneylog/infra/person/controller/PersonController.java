package com.moneylog.infra.person.controller;

import java.net.URI;
import java.util.UUID;

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

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import com.moneylog.core.debt.app.PersonView;
import com.moneylog.core.debt.app.usecase.ArchivePersonUseCase;
import com.moneylog.core.debt.app.usecase.ArchivePersonUseCase.ArchivePersonCommand;
import com.moneylog.core.debt.app.usecase.CreatePersonUseCase;
import com.moneylog.core.debt.app.usecase.CreatePersonUseCase.CreatePersonCommand;
import com.moneylog.core.debt.app.usecase.GetPersonUseCase;
import com.moneylog.core.debt.app.usecase.GetPersonUseCase.GetPersonQuery;
import com.moneylog.core.debt.app.usecase.ListPeopleUseCase;
import com.moneylog.core.debt.app.usecase.ListPeopleUseCase.ListPeopleQuery;
import com.moneylog.core.debt.app.usecase.UnarchivePersonUseCase;
import com.moneylog.core.debt.app.usecase.UnarchivePersonUseCase.UnarchivePersonCommand;
import com.moneylog.core.debt.app.usecase.UpdatePersonUseCase;
import com.moneylog.core.debt.app.usecase.UpdatePersonUseCase.UpdatePersonCommand;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.person.controller.PersonRequests.PersonInput;
import com.moneylog.infra.person.controller.PersonRequests.PersonUpdate;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.SliceResponse;
import com.moneylog.infra.web.validation.NoControlChars;

/** Pessoas (D1 §2.6, S-138, S-142): criar, listar, editar, arquivar e desarquivar. Nenhuma é apagada. */
@RestController
@RequestMapping("/v1/people")
@Validated
public class PersonController {

    private final CreatePersonUseCase create;
    private final UpdatePersonUseCase update;
    private final GetPersonUseCase get;
    private final ListPeopleUseCase list;
    private final ArchivePersonUseCase archive;
    private final UnarchivePersonUseCase unarchive;

    public PersonController(CreatePersonUseCase create, UpdatePersonUseCase update, GetPersonUseCase get, ListPeopleUseCase list,
                            ArchivePersonUseCase archive, UnarchivePersonUseCase unarchive) {
        this.create = create;
        this.update = update;
        this.get = get;
        this.list = list;
        this.archive = archive;
        this.unarchive = unarchive;
    }

    /** {@code status} padrão {@code ACTIVE}. */
    @GetMapping
    public SliceResponse<PersonResponse> list(@CurrentUser UUID userId, @RequestParam(required = false) PersonStatus status,
                                              @RequestParam(required = false) @Size(max = 100) @NoControlChars String q,
                                              PageQuery page) {
        Boolean archived = (status == null ? PersonStatus.ACTIVE : status) == PersonStatus.ARCHIVED;
        return SliceResponse.of(list.handle(new ListPeopleQuery(userId, archived, q, page)), PersonResponse::of);
    }

    @PostMapping
    public ResponseEntity<PersonResponse> create(@CurrentUser UUID userId,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                                 @Valid @RequestBody PersonInput body) {
        PersonView created = create.handle(new CreatePersonCommand(userId, body.name(), body.notes(), idempotencyKey));
        return ResponseEntity.created(URI.create("/v1/people/" + created.person().id())).body(PersonResponse.of(created));
    }

    @GetMapping("/{id}")
    public PersonResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return PersonResponse.detail(get.handle(new GetPersonQuery(userId, id)));
    }

    @PutMapping("/{id}")
    public PersonResponse update(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody PersonUpdate body) {
        return PersonResponse.of(update.handle(new UpdatePersonCommand(userId, id, body.name(), body.notes(), body.version())));
    }

    @PostMapping("/{id}/archive")
    public PersonResponse archive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return PersonResponse.of(archive.handle(new ArchivePersonCommand(userId, id)));
    }

    @PostMapping("/{id}/unarchive")
    public PersonResponse unarchive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return PersonResponse.of(unarchive.handle(new UnarchivePersonCommand(userId, id)));
    }

    /** {@code PersonStatus} do contrato. */
    public enum PersonStatus {
        ACTIVE,
        ARCHIVED
    }
}
