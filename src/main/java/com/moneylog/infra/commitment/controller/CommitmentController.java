package com.moneylog.infra.commitment.controller;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.export.CommitmentByPersonCsv;
import com.moneylog.core.commitment.app.CommitmentByPersonView;
import com.moneylog.core.commitment.app.CommitmentView;
import com.moneylog.core.commitment.app.usecase.CreateCommitmentUseCase;
import com.moneylog.core.commitment.app.usecase.CreateCommitmentUseCase.CreateCommitmentCommand;
import com.moneylog.core.commitment.app.usecase.CreateCommitmentUseCase.ItemInput;
import com.moneylog.core.commitment.app.usecase.GetCommitmentByPersonUseCase;
import com.moneylog.core.commitment.app.usecase.GetCommitmentByPersonUseCase.GetCommitmentByPersonQuery;
import com.moneylog.core.commitment.app.usecase.GetCommitmentUseCase;
import com.moneylog.core.commitment.app.usecase.GetCommitmentUseCase.GetCommitmentQuery;
import com.moneylog.core.commitment.app.usecase.ListCommitmentsUseCase;
import com.moneylog.core.commitment.app.usecase.ListCommitmentsUseCase.ListCommitmentsQuery;
import com.moneylog.core.commitment.app.usecase.MarkScheduleItemPaidUseCase;
import com.moneylog.core.commitment.app.usecase.MarkScheduleItemPaidUseCase.MarkScheduleItemPaidCommand;
import com.moneylog.core.commitment.app.usecase.UpdateCommitmentClausesUseCase;
import com.moneylog.core.commitment.app.usecase.UpdateCommitmentClausesUseCase.UpdateCommitmentClausesCommand;
import com.moneylog.core.commitment.app.usecase.UpdateCommitmentItemSharesUseCase;
import com.moneylog.core.commitment.app.usecase.UpdateCommitmentItemSharesUseCase.UpdateCommitmentItemSharesCommand;
import com.moneylog.core.commitment.app.usecase.UpdateCommitmentProgressUseCase;
import com.moneylog.core.commitment.app.usecase.UpdateCommitmentProgressUseCase.UpdateCommitmentProgressCommand;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.commitment.controller.CommitmentRequests.ClausesUpdate;
import com.moneylog.infra.commitment.controller.CommitmentRequests.CommitmentInput;
import com.moneylog.infra.commitment.controller.CommitmentRequests.MarkPaidInput;
import com.moneylog.infra.commitment.controller.CommitmentRequests.ProgressInput;
import com.moneylog.infra.commitment.controller.CommitmentRequests.ShareInput;
import com.moneylog.infra.commitment.controller.CommitmentRequests.SharesUpdate;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.ProvenanceInput;
import com.moneylog.infra.web.SliceResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/v1/commitments")
@Validated
public class CommitmentController {

    private final CreateCommitmentUseCase create;
    private final GetCommitmentUseCase get;
    private final ListCommitmentsUseCase list;
    private final MarkScheduleItemPaidUseCase markPaid;
    private final UpdateCommitmentProgressUseCase progress;
    private final UpdateCommitmentItemSharesUseCase itemShares;
    private final UpdateCommitmentClausesUseCase clauses;
    private final GetCommitmentByPersonUseCase byPerson;

    @SuppressWarnings("java:S107")
    public CommitmentController(CreateCommitmentUseCase create, GetCommitmentUseCase get, ListCommitmentsUseCase list,
                                MarkScheduleItemPaidUseCase markPaid, UpdateCommitmentProgressUseCase progress,
                                UpdateCommitmentItemSharesUseCase itemShares, UpdateCommitmentClausesUseCase clauses,
                                GetCommitmentByPersonUseCase byPerson) {
        this.itemShares = itemShares;
        this.clauses = clauses;
        this.byPerson = byPerson;
        this.create = create;
        this.get = get;
        this.list = list;
        this.markPaid = markPaid;
        this.progress = progress;
    }

    @GetMapping
    public SliceResponse<CommitmentResponse> list(@CurrentUser UUID userId, PageQuery page) {
        return SliceResponse.of(list.handle(new ListCommitmentsQuery(userId, page)), CommitmentResponse::of);
    }

    @GetMapping("/{id}")
    public CommitmentResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return CommitmentResponse.of(get.handle(new GetCommitmentQuery(userId, id)));
    }

    @PostMapping
    public ResponseEntity<CommitmentResponse> create(@CurrentUser UUID userId,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                                     @Valid @RequestBody CommitmentInput body) {
        List<ItemInput> items = body.schedule() == null ? List.of() : body.schedule().stream()
                .map(i -> new ItemInput(i.label(), i.type(), i.dueDate(), Money.of(i.amount(), CurrencyCode.BRL), i.paid(), i.paidOn(),
                        ShareInput.toDomain(i.shares()), i.coBuyerPersonId(), ProvenanceInput.toDomain(i.provenance()))).toList();
        CommitmentView created = create.handle(new CreateCommitmentCommand(userId, body.kind(), body.name(), body.counterparty(), body.notes(),
                money(body.total()), body.installmentsTotal(), body.installmentsPaid(), money(body.paidAmount()), body.nextDueDate(), items,
                idempotencyKey, body.clauses() == null ? null : body.clauses().toDomain()));
        return ResponseEntity.created(URI.create("/v1/commitments/" + created.commitment().id())).body(CommitmentResponse.of(created));
    }

    @PostMapping("/{id}/schedule/{position}/pay")
    public CommitmentResponse pay(@CurrentUser UUID userId, @PathVariable UUID id, @PathVariable int position,
                                  @Valid @RequestBody MarkPaidInput body) {
        return CommitmentResponse.of(markPaid.handle(new MarkScheduleItemPaidCommand(userId, id, position, body.paidOn(), body.version())));
    }

    @PostMapping("/{id}/progress")
    public CommitmentResponse progress(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody ProgressInput body) {
        return CommitmentResponse.of(progress.handle(new UpdateCommitmentProgressCommand(userId, id, body.installmentsPaid(),
                money(body.paidAmount()), body.nextDueDate(), body.notes(), body.version(), body.name(), body.counterparty())));
    }

    @PutMapping("/{id}/schedule/{position}/shares")
    public CommitmentResponse updateShares(@CurrentUser UUID userId, @PathVariable UUID id, @PathVariable @Min(1) int position,
                                           @Valid @RequestBody SharesUpdate body) {
        return CommitmentResponse.of(itemShares.handle(new UpdateCommitmentItemSharesCommand(userId, id, position,
                ShareInput.toDomain(body.shares()), body.coBuyerPersonId(), body.version())));
    }

    @PutMapping("/{id}/clauses")
    public CommitmentResponse updateClauses(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody ClausesUpdate body) {
        return CommitmentResponse.of(clauses.handle(new UpdateCommitmentClausesCommand(userId, id, body.clauses().toDomain(), body.version())));
    }

    @GetMapping("/{id}/by-person")
    public ResponseEntity<?> byPerson(@CurrentUser UUID userId, @PathVariable UUID id,
                                      @RequestParam(defaultValue = "json") String format,
                                      @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        if (!"json".equals(format) && !"csv".equals(format)) {
            throw new RequestValidationException("format", "INVALID_FORMAT", "'format' aceita json ou csv.");
        }
        CommitmentByPersonView view = byPerson.handle(new GetCommitmentByPersonQuery(userId, id));
        if ("json".equals(format)) {
            return ResponseEntity.ok(CommitmentByPersonResponse.of(view));
        }
        CommitmentByPersonCsv csv = new CommitmentByPersonCsv(view, language(acceptLanguage));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            csv.writeTo(out);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + csv.fileName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(out.toByteArray());
    }

    private static Locale language(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return Locale.forLanguageTag("pt-BR");
        }
        try {
            return Locale.LanguageRange.parse(acceptLanguage).stream().findFirst().map(r -> Locale.forLanguageTag(r.getRange()))
                    .orElse(Locale.forLanguageTag("pt-BR"));
        } catch (IllegalArgumentException e) {
            return Locale.forLanguageTag("pt-BR");
        }
    }

    private static Money money(String value) {
        return value == null ? null : Money.of(value, CurrencyCode.BRL);
    }
}
