package com.moneylog.infra.card.controller;

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

import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.finance.app.cards.usecase.ArchiveCardUseCase;
import com.moneylog.core.finance.app.cards.usecase.ArchiveCardUseCase.ArchiveCardCommand;
import com.moneylog.core.finance.app.cards.usecase.CreateCardUseCase;
import com.moneylog.core.finance.app.cards.usecase.CreateCardUseCase.CreateCardCommand;
import com.moneylog.core.finance.app.cards.usecase.GetCardUseCase;
import com.moneylog.core.finance.app.cards.usecase.GetCardUseCase.GetCardQuery;
import com.moneylog.core.finance.app.cards.usecase.ListCardsUseCase;
import com.moneylog.core.finance.app.cards.usecase.ListCardsUseCase.ListCardsQuery;
import com.moneylog.core.finance.app.cards.usecase.UnarchiveCardUseCase;
import com.moneylog.core.finance.app.cards.usecase.UnarchiveCardUseCase.UnarchiveCardCommand;
import com.moneylog.core.finance.app.cards.usecase.UpdateCardDetailsUseCase;
import com.moneylog.core.finance.app.cards.usecase.UpdateCardDetailsUseCase.UpdateCardDetailsCommand;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.card.controller.CardRequests.CardInput;
import com.moneylog.infra.card.controller.CardRequests.CardUpdate;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.SliceResponse;

@RestController
@RequestMapping("/v1/cards")
public class CardController {

    private final CreateCardUseCase create;
    private final UpdateCardDetailsUseCase update;
    private final ArchiveCardUseCase archive;
    private final com.moneylog.core.finance.app.cards.usecase.SetClosingConfirmationUseCase closing;
    private final UnarchiveCardUseCase unarchive;
    private final GetCardUseCase get;
    private final ListCardsUseCase list;

    public CardController(com.moneylog.core.finance.app.cards.usecase.SetClosingConfirmationUseCase closing, CreateCardUseCase create,
                          UpdateCardDetailsUseCase update, ArchiveCardUseCase archive,
                          UnarchiveCardUseCase unarchive, GetCardUseCase get, ListCardsUseCase list) {
        this.closing = closing;
        this.create = create;
        this.update = update;
        this.archive = archive;
        this.unarchive = unarchive;
        this.get = get;
        this.list = list;
    }

    @GetMapping
    public SliceResponse<CardResponse> list(@CurrentUser UUID userId, @RequestParam(required = false) CardStatus status,
                                            PageQuery page) {
        return SliceResponse.of(list.handle(new ListCardsQuery(userId, status, page)), CardResponse::of);
    }

    @PostMapping
    public ResponseEntity<CardResponse> create(@CurrentUser UUID userId, @Valid @RequestBody CardInput body) {
        CardDetails created = create.handle(new CreateCardCommand(userId, body.name(), body.institution(), body.limit(),
                body.closingDay(), body.dueDay(), body.defaultPaymentAccountId()));
        return ResponseEntity.created(URI.create("/v1/cards/" + created.card().id())).body(CardResponse.of(created));
    }

    @GetMapping("/{id}")
    public CardResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return CardResponse.of(get.handle(new GetCardQuery(userId, id)));
    }

    @PutMapping("/{id}")
    public CardResponse update(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody CardUpdate body) {
        return CardResponse.of(update.handle(new UpdateCardDetailsCommand(userId, id, body.name(), body.institution(),
                body.limit(), body.closingDay(), body.dueDay(), body.defaultPaymentAccountId(), body.confirmedEffectiveFrom(), body.version())));
    }

    public record ClosingConfirmationInput(@jakarta.validation.constraints.NotNull Boolean confirmed,
                                           @jakarta.validation.constraints.NotNull Long version) {
    }

    @PostMapping("/{id}/closing-confirmation")
    public CardResponse closingConfirmation(@CurrentUser UUID userId, @PathVariable UUID id,
                                            @Valid @RequestBody ClosingConfirmationInput body) {
        return CardResponse.of(closing.handle(new com.moneylog.core.finance.app.cards.usecase.SetClosingConfirmationUseCase
                .SetClosingConfirmationCommand(userId, id, body.confirmed(), body.version())));
    }

    @PostMapping("/{id}/archive")
    public CardResponse archive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return CardResponse.of(archive.handle(new ArchiveCardCommand(userId, id)));
    }

    @PostMapping("/{id}/unarchive")
    public CardResponse unarchive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return CardResponse.of(unarchive.handle(new UnarchiveCardCommand(userId, id)));
    }
}
