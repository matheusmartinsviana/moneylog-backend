package com.moneylog.infra.goal.controller;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.usecase.AnchorDebtPayoffBaselineUseCase;
import com.moneylog.core.finance.app.goals.usecase.AnchorDebtPayoffBaselineUseCase.AnchorDebtPayoffBaselineCommand;
import com.moneylog.core.finance.app.goals.usecase.ArchiveGoalUseCase;
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

    @SuppressWarnings("java:S107")
    public GoalController(CreateGoalUseCase create, UpdateGoalUseCase update, GetGoalUseCase get, ListGoalsUseCase list,
                          ReserveOnGoalUseCase reserve, ArchiveGoalUseCase archive, UnarchiveGoalUseCase unarchive,
                          AnchorDebtPayoffBaselineUseCase baseline) {
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
    public SliceResponse<GoalResponse> list(@CurrentUser UUID userId, @RequestParam(required = false) GoalStatus status, PageQuery page) {
        return SliceResponse.of(list.handle(new ListGoalsQuery(userId, status, page)), GoalResponse::of);
    }

    @PostMapping
    public ResponseEntity<GoalResponse> create(@CurrentUser UUID userId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                               @Valid @RequestBody GoalInput body) {
        boolean payoff = body.kind() == GoalKind.DEBT_PAYOFF;
        if (!payoff) {
            List<FieldIssue> issues = new ArrayList<>();
            if (body.name() == null || body.name().isBlank()) {
                issues.add(new FieldIssue("name", "NotBlank", "must not be blank"));
            }
            if (body.target() == null) {
                issues.add(new FieldIssue("target", "NotNull", "must not be null"));
            }
            if (!issues.isEmpty()) {
                throw new RequestValidationException(issues);
            }
        }
        GoalDetails created = create.handle(new CreateGoalCommand(userId, body.name(),
                body.target() == null ? null : Money.of(body.target(), CurrencyCode.BRL), body.deadline(), body.priority(),
                body.description(), idempotencyKey, body.kind()));
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
                body.deadline(), body.priority(), body.description(), body.version())));
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

    @PostMapping("/{id}/archive")
    public GoalResponse archive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return GoalResponse.of(archive.handle(new ArchiveGoalCommand(userId, id)));
    }

    @PostMapping("/{id}/unarchive")
    public GoalResponse unarchive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return GoalResponse.of(unarchive.handle(new UnarchiveGoalCommand(userId, id)));
    }
}
