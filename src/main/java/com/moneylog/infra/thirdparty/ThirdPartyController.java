package com.moneylog.infra.thirdparty;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.debts.usecase.GetThirdPartyReceivablesUseCase;
import com.moneylog.core.finance.app.debts.usecase.GetThirdPartyReceivablesUseCase.GetThirdPartyReceivablesQuery;
import com.moneylog.core.finance.domain.ThirdPartyReceivables;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.thirdparty.app.MarkThirdPartyExpenseUseCase;
import com.moneylog.core.thirdparty.app.MarkThirdPartyExpenseUseCase.MarkThirdPartyExpenseCommand;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Despesas de terceiro e "Dinheiro a receber de terceiros": só do usuário do token; tudo calculado no backend. */
@RestController
public class ThirdPartyController {

    private final MarkThirdPartyExpenseUseCase mark;
    private final GetThirdPartyReceivablesUseCase overview;

    public ThirdPartyController(MarkThirdPartyExpenseUseCase mark, GetThirdPartyReceivablesUseCase overview) {
        this.mark = mark;
        this.overview = overview;
    }

    public record MarkInput(@NotNull UUID transactionId, @NotNull UUID personId, @Size(max = 200) @NoControlChars String note) {
    }

    public record MarkResponse(UUID id, UUID transactionId, UUID personId, String note) {
    }

    public record ItemResponse(ThirdPartyReceivables.Source source, UUID refId, String description, LocalDate date, LocalDate expectedDate,
                               Money owed, Money received, Money pending, String status) {
    }

    public record PersonResponse(UUID personId, String name, Money owed, Money received, Money pending, List<ItemResponse> items) {
    }

    public record MonthResponse(String competence, Money grossConsumption, Money refunds, Money personalNet, Money thirdPartyConsumption,
                                Money thirdPartyReimbursed, Money thirdPartyPending) {
    }

    public record OverviewResponse(LocalDate asOf, Money owed, Money received, Money pending, List<PersonResponse> people,
                                   MonthResponse month) {
    }

    /** Marca a compra/despesa como de uma pessoa (responsável real). Não muda lançamentos nem o consumo. */
    @PostMapping("/v1/third-party-expenses")
    public ResponseEntity<MarkResponse> mark(@CurrentUser UUID userId,
                                             @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                             @Valid @RequestBody MarkInput body) {
        ThirdPartyExpense created = mark.handle(new MarkThirdPartyExpenseCommand(userId, body.transactionId(), body.personId(), body.note(),
                idempotencyKey));
        return ResponseEntity.created(URI.create("/v1/transactions/" + created.transactionId()))
                .body(new MarkResponse(created.id(), created.transactionId(), created.personId(), created.note()));
    }

    @GetMapping("/v1/debts/third-party-receivables")
    public OverviewResponse get(@CurrentUser UUID userId, @RequestParam(required = false) String month) {
        Competence competence;
        try {
            competence = month == null ? null : Competence.parse(month);
        } catch (RuntimeException e) {
            throw new RequestValidationException("month", "INVALID_FORMAT", "'month' deve estar no formato AAAA-MM.");
        }
        ThirdPartyReceivables.Overview o = overview.handle(new GetThirdPartyReceivablesQuery(userId, competence));
        ThirdPartyReceivables.MonthView m = o.month();
        return new OverviewResponse(o.asOf(), o.owed(), o.received(), o.pending(), o.people().stream()
                .map(p -> new PersonResponse(p.personId(), p.name(), p.owed(), p.received(), p.pending(), p.items().stream()
                        .map(i -> new ItemResponse(i.source(), i.refId(), i.description(), i.date(), i.expectedDate(), i.owed(),
                                i.received(), i.pending(), i.status())).toList())).toList(),
                new MonthResponse(m.competence().toString(), m.grossConsumption(), m.refunds(), m.personalNet(), m.thirdPartyConsumption(),
                        m.thirdPartyReimbursed(), m.thirdPartyPending()));
    }
}
