package com.moneylog.infra.goal.controller;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.usecase.AnchorDebtPayoffBaselineUseCase;
import com.moneylog.core.finance.app.goals.usecase.AnchorDebtPayoffBaselineUseCase.AnchorDebtPayoffBaselineCommand;
import com.moneylog.core.finance.app.goals.usecase.ArchiveGoalUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.AddGoalActionUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.AddGoalActionUseCase.AddGoalActionCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.CheckInGoalActionUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.CheckInGoalActionUseCase.CheckInGoalActionCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.DeleteGoalActionUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.DeleteGoalActionUseCase.DeleteGoalActionCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.UpdateGoalActionUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.UpdateGoalActionUseCase.UpdateGoalActionCommand;
import com.moneylog.core.finance.app.goals.usecase.ArchiveGoalUseCase.ArchiveGoalCommand;
import com.moneylog.core.finance.app.goals.usecase.CreateGoalUseCase;
import com.moneylog.core.finance.app.goals.usecase.CreateGoalUseCase.CreateGoalCommand;
import com.moneylog.core.finance.app.goals.usecase.GetGoalUseCase;
import com.moneylog.core.finance.app.goals.usecase.GetGoalUseCase.GetGoalQuery;
import com.moneylog.core.finance.app.goals.usecase.ListGoalsUseCase;
import com.moneylog.core.finance.app.goals.usecase.ListGoalsUseCase.ListGoalsQuery;
import com.moneylog.core.finance.app.goals.usecase.ReserveOnGoalUseCase;
import com.moneylog.core.finance.app.goals.usecase.ReserveOnGoalUseCase.ReserveOnGoalCommand;
import com.moneylog.core.finance.app.goals.usecase.UnarchiveGoalUseCase;
import com.moneylog.core.finance.app.goals.usecase.UnarchiveGoalUseCase.UnarchiveGoalCommand;
import com.moneylog.core.finance.app.goals.usecase.UpdateGoalUseCase;
import com.moneylog.core.finance.app.goals.usecase.UpdateGoalUseCase.UpdateGoalCommand;
import com.moneylog.core.goal.domain.GoalKind;
import com.moneylog.core.goal.domain.GoalStatus;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.goal.controller.GoalRequests.GoalActionInput;
import com.moneylog.infra.goal.controller.GoalRequests.GoalCheckInInput;
import com.moneylog.infra.goal.controller.GoalRequests.GoalInput;
import com.moneylog.infra.goal.controller.GoalRequests.GoalReservationInput;
import com.moneylog.infra.goal.controller.GoalRequests.GoalUpdate;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.exception.RequestValidationException.FieldIssue;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.SliceResponse;

/**
 * Metas e reservas (D1 §2.9, S-147): reserva <b>lógica</b>, sem movimentação nem lançamento. Reservar e liberar devolvem a meta
 * atualizada ({@code 200}).
 */
@RestController
@RequestMapping("/v1/goals")
public class GoalController {

    private final CreateGoalUseCase create;
    private final UpdateGoalUseCase update;
    private final GetGoalUseCase get;
    private final ListGoalsUseCase list;
    private final ReserveOnGoalUseCase reserve;
    private final ArchiveGoalUseCase archive;
    private final UnarchiveGoalUseCase unarchive;
    private final AnchorDebtPayoffBaselineUseCase baseline;
    private final AddGoalActionUseCase addAction;
    private final UpdateGoalActionUseCase updateAction;
    private final DeleteGoalActionUseCase deleteAction;
    private final CheckInGoalActionUseCase checkIn;

    @SuppressWarnings("java:S107")
    public GoalController(CreateGoalUseCase create, UpdateGoalUseCase update, GetGoalUseCase get, ListGoalsUseCase list,
                          ReserveOnGoalUseCase reserve, ArchiveGoalUseCase archive, UnarchiveGoalUseCase unarchive,
                          AnchorDebtPayoffBaselineUseCase baseline, AddGoalActionUseCase addAction, UpdateGoalActionUseCase updateAction,
                          DeleteGoalActionUseCase deleteAction, CheckInGoalActionUseCase checkIn) {
        this.addAction = addAction;
        this.updateAction = updateAction;
        this.deleteAction = deleteAction;
        this.checkIn = checkIn;
        this.baseline = baseline;
        this.create = create;
        this.update = update;
        this.get = get;
        this.list = list;
        this.reserve = reserve;
        this.archive = archive;
        this.unarchive = unarchive;
    }

    @GetMapping
    public SliceResponse<GoalResponse> list(@CurrentUser UUID userId, @RequestParam(required = false) GoalStatus status,
                                            @RequestParam(required = false) Boolean featured, PageQuery page) {
        return SliceResponse.of(list.handle(new ListGoalsQuery(userId, status, featured, page)), GoalResponse::of);
    }

    @PostMapping
    public ResponseEntity<GoalResponse> create(@CurrentUser UUID userId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                               @Valid @RequestBody GoalInput body) {
        List<FieldIssue> issues = new ArrayList<>();
        if (body.kind() == GoalKind.METRIC) {
            if (body.metric() == null) {
                issues.add(new FieldIssue("metric", "NotNull", "must not be null"));
            }
            if (body.target() == null) {
                issues.add(new FieldIssue("target", "NotNull", "must not be null"));
            }
        } else if (body.kind() != GoalKind.DEBT_PAYOFF) {
            if (body.name() == null || body.name().isBlank()) {
                issues.add(new FieldIssue("name", "NotBlank", "must not be blank"));
            }
            if (body.target() == null) {
                issues.add(new FieldIssue("target", "NotNull", "must not be null"));
            }
        }
        if (!issues.isEmpty()) {
            throw new RequestValidationException(issues);
        }
        GoalDetails created = create.handle(new CreateGoalCommand(userId, body.name(),
                body.target() == null ? null : Money.of(body.target(), CurrencyCode.BRL), body.deadline(), body.priority(),
                body.description(), idempotencyKey, body.kind(), body.kind() == GoalKind.METRIC ? body.metric() : null));
        return ResponseEntity.created(URI.create("/v1/goals/" + created.goal().id())).body(GoalResponse.of(created));
    }

    @GetMapping("/{id}")
    public GoalResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return GoalResponse.of(get.handle(new GetGoalQuery(userId, id)));
    }

    @PutMapping("/{id}")
    public GoalResponse update(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody GoalUpdate body) {
        return GoalResponse.of(update.handle(new UpdateGoalCommand(userId, id, body.name(),
                body.target() == null ? null : Money.of(body.target(), CurrencyCode.BRL),
                body.deadline(), body.priority(), body.description(), body.version(), body.planNote())));
    }

    /** Reserva (positivo) ou libera (negativo) dinheiro de uma conta; não cria movimentação nem lançamento. */
    @PostMapping("/{id}/reservations")
    public GoalResponse reserve(@CurrentUser UUID userId, @PathVariable UUID id, @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                @Valid @RequestBody GoalReservationInput body) {
        return GoalResponse.of(reserve.handle(new ReserveOnGoalCommand(userId, id, body.accountId(),
                Money.of(body.amount(), CurrencyCode.BRL), body.date(), body.note(), idempotencyKey)));
    }

    /** "Cadastrei tudo" (ADR-013): fixa a baseline da meta de quitar dívidas com o total de hoje; repetir re-ancora. */
    @PostMapping("/{id}/baseline")
    public GoalResponse baseline(@CurrentUser UUID userId, @PathVariable UUID id) {
        return GoalResponse.of(baseline.handle(new AnchorDebtPayoffBaselineCommand(userId, id)));
    }

    /** Acrescenta uma ação ao plano declarado (ADR-016); devolve a meta atualizada. */
    @PostMapping("/{id}/actions")
    public ResponseEntity<GoalResponse> addAction(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody GoalActionInput body) {
        GoalDetails details = addAction.handle(new AddGoalActionCommand(userId, id, body.title(), body.frequency()));
        return ResponseEntity.created(URI.create("/v1/goals/" + id)).body(GoalResponse.of(details));
    }

    @PutMapping("/{id}/actions/{actionId}")
    public GoalResponse updateAction(@CurrentUser UUID userId, @PathVariable UUID id, @PathVariable UUID actionId,
                                     @Valid @RequestBody GoalActionInput body) {
        return GoalResponse.of(updateAction.handle(new UpdateGoalActionCommand(userId, id, actionId, body.title(), body.frequency())));
    }

    @DeleteMapping("/{id}/actions/{actionId}")
    public GoalResponse deleteAction(@CurrentUser UUID userId, @PathVariable UUID id, @PathVariable UUID actionId) {
        return GoalResponse.of(deleteAction.handle(new DeleteGoalActionCommand(userId, id, actionId)));
    }

    /** Marca (ou desmarca com {@code done=false}) o período da ação; idempotente. */
    @PostMapping("/{id}/actions/{actionId}/check-in")
    public GoalResponse checkIn(@CurrentUser UUID userId, @PathVariable UUID id, @PathVariable UUID actionId,
                                @Valid @RequestBody GoalCheckInInput body) {
        return GoalResponse.of(checkIn.handle(new CheckInGoalActionCommand(userId, id, actionId, body.period(),
                body.done() == null || body.done())));
    }

    @PostMapping("/{id}/archive")
    public GoalResponse archive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return GoalResponse.of(archive.handle(new ArchiveGoalCommand(userId, id)));
    }

    @PostMapping("/{id}/unarchive")
    public GoalResponse unarchive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return GoalResponse.of(unarchive.handle(new UnarchiveGoalCommand(userId, id)));
    }
}
