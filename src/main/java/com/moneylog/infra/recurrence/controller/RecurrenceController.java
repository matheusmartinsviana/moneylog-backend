package com.moneylog.infra.recurrence.controller;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
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

import com.moneylog.core.finance.app.recurrences.usecase.RealizeOccurrenceUseCase;
import com.moneylog.core.finance.app.recurrences.usecase.RealizeOccurrenceUseCase.RealizeOccurrenceCommand;
import com.moneylog.core.finance.app.recurrences.usecase.SkipOccurrenceUseCase;
import com.moneylog.core.finance.app.recurrences.usecase.SkipOccurrenceUseCase.SkipOccurrenceCommand;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.app.usecase.CreateRecurrenceUseCase;
import com.moneylog.core.recurrence.app.usecase.CreateRecurrenceUseCase.CreateRecurrenceCommand;
import com.moneylog.core.recurrence.app.usecase.DeactivateRecurrenceUseCase;
import com.moneylog.core.recurrence.app.usecase.DeactivateRecurrenceUseCase.DeactivateRecurrenceCommand;
import com.moneylog.core.recurrence.app.usecase.EndRecurrenceUseCase;
import com.moneylog.core.recurrence.app.usecase.EndRecurrenceUseCase.EndRecurrenceCommand;
import com.moneylog.core.recurrence.app.usecase.GetRecurrenceSummaryUseCase;
import com.moneylog.core.recurrence.app.usecase.GetRecurrenceSummaryUseCase.GetRecurrenceSummaryQuery;
import com.moneylog.core.recurrence.app.usecase.GetRecurrenceUseCase;
import com.moneylog.core.recurrence.app.usecase.GetRecurrenceUseCase.GetRecurrenceQuery;
import com.moneylog.core.recurrence.app.usecase.ListOccurrencesUseCase;
import com.moneylog.core.recurrence.app.usecase.ListOccurrencesUseCase.ListOccurrencesQuery;
import com.moneylog.core.recurrence.app.usecase.ListRecurrencesUseCase;
import com.moneylog.core.recurrence.app.usecase.ListRecurrencesUseCase.ListRecurrencesQuery;
import com.moneylog.core.recurrence.app.usecase.UpdateRecurrenceUseCase;
import com.moneylog.core.recurrence.app.usecase.UpdateRecurrenceUseCase.UpdateRecurrenceCommand;
import com.moneylog.core.recurrence.domain.MonthlyEstimate;
import com.moneylog.core.finance.domain.ConsumptionView;
import com.moneylog.infra.web.QueryViews;
import com.moneylog.core.recurrence.domain.OccurrenceStatus;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.transaction.domain.TransactionNature;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.infra.web.ProvenanceInput;
import com.moneylog.infra.web.QueryDates;
import com.moneylog.infra.recurrence.controller.RecurrenceRequests.EndInput;
import com.moneylog.infra.recurrence.controller.RecurrenceRequests.RealizeInput;
import com.moneylog.infra.recurrence.controller.RecurrenceRequests.RecurrenceInput;
import com.moneylog.infra.recurrence.controller.RecurrenceRequests.RecurrenceUpdate;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.transaction.controller.PersonShareInput;
import com.moneylog.infra.transaction.controller.TransactionResponse;
import com.moneylog.infra.web.SliceResponse;
import com.moneylog.core.sk.time.TimeProvider;

@RestController
@RequestMapping("/v1/recurrences")
public class RecurrenceController {

    private final CreateRecurrenceUseCase create;
    private final UpdateRecurrenceUseCase update;
    private final EndRecurrenceUseCase end;
    private final DeactivateRecurrenceUseCase deactivate;
    private final GetRecurrenceUseCase get;
    private final ListRecurrencesUseCase list;
    private final ListOccurrencesUseCase occurrences;
    private final GetRecurrenceSummaryUseCase summary;
    private final RealizeOccurrenceUseCase realize;
    private final SkipOccurrenceUseCase skip;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public RecurrenceController(CreateRecurrenceUseCase create, UpdateRecurrenceUseCase update, EndRecurrenceUseCase end,
                                DeactivateRecurrenceUseCase deactivate, GetRecurrenceUseCase get, ListRecurrencesUseCase list,
                                ListOccurrencesUseCase occurrences, GetRecurrenceSummaryUseCase summary,
                                RealizeOccurrenceUseCase realize, SkipOccurrenceUseCase skip, TimeProvider time) {
        this.time = time;
        this.create = create;
        this.update = update;
        this.end = end;
        this.deactivate = deactivate;
        this.get = get;
        this.list = list;
        this.occurrences = occurrences;
        this.summary = summary;
        this.realize = realize;
        this.skip = skip;
    }

    @GetMapping
    public SliceResponse<RecurrenceResponse> list(@CurrentUser UUID userId, @RequestParam(required = false) Boolean active,
                                                  PageQuery page) {
        return SliceResponse.of(list.handle(new ListRecurrencesQuery(userId, active, page)), RecurrenceResponse::of);
    }

    @PostMapping
    public ResponseEntity<RecurrenceResponse> create(@CurrentUser UUID userId,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                                     @Valid @RequestBody RecurrenceInput body) {
        RecurrenceView created = create.handle(new CreateRecurrenceCommand(userId, TransactionType.valueOf(body.type().name()),
                Money.of(body.amount(), CurrencyCode.BRL), body.description(), body.categoryId(), body.accountId(), body.method(),
                body.cardId(), body.frequency(), body.interval(), body.unit(), body.startDate(), body.endDate(),
                body.maxOccurrences(), idempotencyKey, body.incomeCompetenceOffsetMonths(),
                TransactionNature.parse(body.nature()), body.splitMode(), PersonShareInput.lines(body.shares()), body.foreign(),
                body.subscription(), ProvenanceInput.toDomain(body.provenance())));
        return ResponseEntity.created(URI.create("/v1/recurrences/" + created.recurrence().id())).body(RecurrenceResponse.of(created));
    }

    @GetMapping("/summary")
    public RecurrenceSummaryResponse summary(@CurrentUser UUID userId, @RequestParam(required = false) String view) {
        ConsumptionView parsed = QueryViews.parse(view);
        MonthlyEstimate estimate = summary.handle(new GetRecurrenceSummaryQuery(userId, parsed == ConsumptionView.GROSS));
        return new RecurrenceSummaryResponse(parsed, estimate.fixedCosts(), estimate.recurringIncome(), estimate.estimate());
    }

    @GetMapping("/occurrences")
    public OccurrenceResponse.Slice occurrences(@CurrentUser UUID userId,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                         @RequestParam(required = false) OccurrenceStatus status, PageQuery page) {
        QueryDates.inWindow(from, "from");
        QueryDates.inWindow(to, "to");
        if (to != null) {
            QueryDates.range(from != null ? from : (to.isBefore(time.today()) ? to : time.today()), to);
        }
        return OccurrenceResponse.Slice.of(occurrences.handle(new ListOccurrencesQuery(userId, from, to, status, page)));
    }

    @GetMapping("/{id}")
    public RecurrenceResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return RecurrenceResponse.of(get.handle(new GetRecurrenceQuery(userId, id)));
    }

    @PutMapping("/{id}")
    public RecurrenceResponse update(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody RecurrenceUpdate body) {
        return RecurrenceResponse.of(update.handle(new UpdateRecurrenceCommand(userId, id, Money.of(body.amount(), CurrencyCode.BRL),
                body.description(), body.categoryId(), body.accountId(), body.method(), body.cardId(), body.endDate(), body.version(),
                body.incomeCompetenceOffsetMonths(), TransactionNature.parse(body.nature()), body.splitMode(),
                body.shares() == null ? null : PersonShareInput.lines(body.shares()), body.foreign(), body.subscription(),
                ProvenanceInput.toDomain(body.provenance()))));
    }

    @PostMapping("/{id}/end")
    public RecurrenceResponse end(@CurrentUser UUID userId, @PathVariable UUID id,
                                  @Valid @RequestBody(required = false) EndInput body) {
        return RecurrenceResponse.of(end.handle(new EndRecurrenceCommand(userId, id, body == null ? null : body.endDate(),
                body == null ? null : body.activeUntil())));
    }

    @PostMapping("/{id}/deactivate")
    public RecurrenceResponse deactivate(@CurrentUser UUID userId, @PathVariable UUID id) {
        return RecurrenceResponse.of(deactivate.handle(new DeactivateRecurrenceCommand(userId, id)));
    }

    @PostMapping("/{id}/occurrences/{date}/realize")
    public ResponseEntity<TransactionResponse> realize(@CurrentUser UUID userId, @PathVariable UUID id,
                                                       @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                       @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                                       @Valid @RequestBody(required = false) RealizeInput body) {
        Money amount = body == null || body.amount() == null ? null : Money.of(body.amount(), CurrencyCode.BRL);
        TransactionDetails created = realize.handle(new RealizeOccurrenceCommand(userId, id, date, amount, idempotencyKey,
                body == null ? null : body.accountId(), body == null ? null : body.method(), body == null ? null : body.foreign()));
        return ResponseEntity.created(URI.create("/v1/transactions/" + created.transaction().id())).body(TransactionResponse.of(created));
    }

    @PostMapping("/{id}/occurrences/{date}/skip")
    public OccurrenceResponse skip(@CurrentUser UUID userId, @PathVariable UUID id,
                                   @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return OccurrenceResponse.of(skip.handle(new SkipOccurrenceCommand(userId, id, date)));
    }
}
