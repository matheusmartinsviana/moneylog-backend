package com.moneylog.infra.commitment.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.commitment.app.CommitmentView;
import com.moneylog.core.commitment.app.usecase.CreateCommitmentUseCase;
import com.moneylog.core.commitment.app.usecase.CreateCommitmentUseCase.CreateCommitmentCommand;
import com.moneylog.core.commitment.app.usecase.CreateCommitmentUseCase.ItemInput;
import com.moneylog.core.commitment.app.usecase.GetCommitmentUseCase;
import com.moneylog.core.commitment.app.usecase.GetCommitmentUseCase.GetCommitmentQuery;
import com.moneylog.core.commitment.app.usecase.ListCommitmentsUseCase;
import com.moneylog.core.commitment.app.usecase.ListCommitmentsUseCase.ListCommitmentsQuery;
import com.moneylog.core.commitment.app.usecase.MarkScheduleItemPaidUseCase;
import com.moneylog.core.commitment.app.usecase.MarkScheduleItemPaidUseCase.MarkScheduleItemPaidCommand;
import com.moneylog.core.commitment.app.usecase.UpdateCommitmentProgressUseCase;
import com.moneylog.core.commitment.app.usecase.UpdateCommitmentProgressUseCase.UpdateCommitmentProgressCommand;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.commitment.controller.CommitmentRequests.CommitmentInput;
import com.moneylog.infra.commitment.controller.CommitmentRequests.MarkPaidInput;
import com.moneylog.infra.commitment.controller.CommitmentRequests.ProgressInput;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.SliceResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/commitments")
@Validated
public class CommitmentController {

    private final CreateCommitmentUseCase create;
    private final GetCommitmentUseCase get;
    private final ListCommitmentsUseCase list;
    private final MarkScheduleItemPaidUseCase markPaid;
    private final UpdateCommitmentProgressUseCase progress;

    public CommitmentController(CreateCommitmentUseCase create, GetCommitmentUseCase get, ListCommitmentsUseCase list,
                                MarkScheduleItemPaidUseCase markPaid, UpdateCommitmentProgressUseCase progress) {
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
                .map(i -> new ItemInput(i.label(), i.type(), i.dueDate(), Money.of(i.amount(), CurrencyCode.BRL), i.paid(), i.paidOn())).toList();
        CommitmentView created = create.handle(new CreateCommitmentCommand(userId, body.kind(), body.name(), body.counterparty(), body.notes(),
                money(body.total()), body.installmentsTotal(), body.installmentsPaid(), money(body.paidAmount()), body.nextDueDate(), items,
                idempotencyKey));
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

    private static Money money(String value) {
        return value == null ? null : Money.of(value, CurrencyCode.BRL);
    }
}
