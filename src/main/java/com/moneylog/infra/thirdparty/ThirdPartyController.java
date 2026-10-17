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
import com.moneylog.core.thirdparty.domain.ReceiptLedgerEffect;
import com.moneylog.core.thirdparty.domain.ReceiptSettlement;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
public class ThirdPartyController {

    private final MarkThirdPartyExpenseUseCase mark;
    private final GetThirdPartyReceivablesUseCase overview;
    private final com.moneylog.core.thirdparty.app.UnmarkThirdPartyExpenseUseCase unmark;
    private final com.moneylog.core.thirdparty.app.RecordThirdPartyReceiptUseCase receipt;
    private final com.moneylog.core.finance.app.debts.usecase.SetReceivableExpectedMonthUseCase setExpectedMonth;

    public ThirdPartyController(MarkThirdPartyExpenseUseCase mark, GetThirdPartyReceivablesUseCase overview,
                                com.moneylog.core.thirdparty.app.UnmarkThirdPartyExpenseUseCase unmark,
                                com.moneylog.core.thirdparty.app.RecordThirdPartyReceiptUseCase receipt,
                                com.moneylog.core.finance.app.debts.usecase.SetReceivableExpectedMonthUseCase setExpectedMonth) {
        this.setExpectedMonth = setExpectedMonth;
        this.receipt = receipt;
        this.unmark = unmark;
        this.mark = mark;
        this.overview = overview;
    }

    public record MarkInput(@NotNull UUID transactionId, @NotNull UUID personId, @Size(max = 200) @NoControlChars String note,
                            @jakarta.validation.constraints.Pattern(regexp = "^\\d{1,13}(\\.\\d{1,2})?$") String amount) {
    }

    public record MarkResponse(UUID id, UUID transactionId, UUID personId, String note, Money amount) {
    }

    public record ReceiptInput(@NotNull UUID personId, UUID transactionId,
                               @NotNull @jakarta.validation.constraints.Pattern(regexp = "^\\d{1,13}(\\.\\d{1,2})?$") String amount,
                               @com.moneylog.infra.web.validation.DateInWindow LocalDate receivedOn, @Size(max = 200) @NoControlChars String note,
                               UUID accountId) {
    }

    public record ReceiptResponse(UUID id, UUID personId, UUID transactionId, Money amount, LocalDate receivedOn, String note,
                                  ReceiptSettlement settlement, ReceiptLedgerEffect ledgerEffect) {
    }

    public record InstallmentResponse(UUID installmentId, int number, int total, String consumptionMonth, String dueMonth,
                                      String expectedMonth, boolean overridden, Money owed, Money received, Money pending) {

        static InstallmentResponse of(com.moneylog.core.finance.domain.CardShareReceivables.Part p) {
            return new InstallmentResponse(p.installmentId(), p.number(), p.total(), p.consumption().toString(), p.dueMonth() == null ? null : p.dueMonth().toString(),
                    p.expectedMonth().toString(), p.overridden(), p.owed(), p.received(), p.pending());
        }
    }

    public record ItemResponse(ThirdPartyReceivables.Source source, UUID refId, String description, LocalDate date, LocalDate expectedDate,
                               Money owed, Money received, Money pending, String status, List<InstallmentResponse> installments) {
    }

    public record ExpectedMonthInput(@jakarta.validation.constraints.Pattern(regexp = "^\\d{4}-\\d{2}$") String expectedMonth, Long version) {
    }

    public record ExpectedMonthResponse(UUID transactionId, UUID personId, InstallmentResponse installment, long version) {
    }

    public record PersonMonthResponse(String competence, Money owed, Money received, Money pending) {
    }

    public record PersonResponse(UUID personId, String name, Money owed, Money received, Money pending, List<ItemResponse> items,
                                 List<PersonMonthResponse> months) {
    }

    public record MonthResponse(String competence, Money grossConsumption, Money refunds, Money personalNet, Money thirdPartyConsumption,
                                Money thirdPartyReimbursed, Money thirdPartyPending) {
    }

    public record OverviewResponse(LocalDate asOf, Money owed, Money received, Money pending, List<PersonResponse> people,
                                   MonthResponse month) {
    }

    @PostMapping("/v1/third-party-expenses")
    public ResponseEntity<MarkResponse> mark(@CurrentUser UUID userId,
                                             @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                             @Valid @RequestBody MarkInput body) {
        ThirdPartyExpense created = mark.handle(new MarkThirdPartyExpenseCommand(userId, body.transactionId(), body.personId(), body.note(),
                body.amount() == null ? null : Money.of(body.amount(), com.moneylog.core.sk.money.CurrencyCode.BRL), idempotencyKey));
        return ResponseEntity.created(URI.create("/v1/transactions/" + created.transactionId()))
                .body(new MarkResponse(created.id(), created.transactionId(), created.personId(), created.note(), created.amount()));
    }

    @PostMapping("/v1/third-party-receipts")
    public ResponseEntity<ReceiptResponse> receipt(@CurrentUser UUID userId,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                                   @Valid @RequestBody ReceiptInput body) {
        var created = receipt.handle(new com.moneylog.core.thirdparty.app.RecordThirdPartyReceiptUseCase.RecordThirdPartyReceiptCommand(userId,
                body.personId(), body.transactionId(), Money.of(body.amount(), com.moneylog.core.sk.money.CurrencyCode.BRL), body.receivedOn(),
                body.note(), body.accountId(), idempotencyKey));
        return ResponseEntity.created(URI.create("/v1/debts/third-party-receivables"))
                .body(new ReceiptResponse(created.id(), created.personId(), created.transactionId(), created.amount(), created.receivedOn(), created.note(), created.settlement(), created.ledgerEffect()));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/v1/third-party-receipts/{id}")
    public ResponseEntity<Void> deleteReceipt(@CurrentUser UUID userId, @org.springframework.web.bind.annotation.PathVariable UUID id) {
        unmark.deleteReceipt(new com.moneylog.core.thirdparty.app.UnmarkThirdPartyExpenseUseCase.UnmarkThirdPartyExpenseCommand(userId, id));
        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/v1/third-party-expenses/{id}")
    public ResponseEntity<Void> unmark(@CurrentUser UUID userId, @org.springframework.web.bind.annotation.PathVariable UUID id) {
        unmark.handle(new com.moneylog.core.thirdparty.app.UnmarkThirdPartyExpenseUseCase.UnmarkThirdPartyExpenseCommand(userId, id));
        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.PutMapping("/v1/third-party-receivables/{transactionId}/installments/{installmentId}/people/{personId}/expected-month")
    public ExpectedMonthResponse setExpectedMonth(@CurrentUser UUID userId, @org.springframework.web.bind.annotation.PathVariable UUID transactionId,
                                                  @org.springframework.web.bind.annotation.PathVariable UUID installmentId,
                                                  @org.springframework.web.bind.annotation.PathVariable UUID personId,
                                                  @Valid @RequestBody ExpectedMonthInput body) {
        Competence month;
        try {
            month = body.expectedMonth() == null ? null : Competence.parse(body.expectedMonth());
        } catch (RuntimeException e) {
            throw new RequestValidationException("expectedMonth", "INVALID_FORMAT", "'expectedMonth' deve estar no formato AAAA-MM.");
        }
        var result = setExpectedMonth.handle(new com.moneylog.core.finance.app.debts.usecase.SetReceivableExpectedMonthUseCase
                .SetReceivableExpectedMonthCommand(userId, transactionId, installmentId, personId, month, body.version()));
        return new ExpectedMonthResponse(result.transactionId(), result.personId(), InstallmentResponse.of(result.installment()), result.version());
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
                                i.received(), i.pending(), i.status(),
                                i.installments().stream().map(InstallmentResponse::of).toList())).toList(),
                        p.months().stream().map(pm -> new PersonMonthResponse(pm.competence().toString(), pm.owed(), pm.received(), pm.pending())).toList())).toList(),
                new MonthResponse(m.competence().toString(), m.grossConsumption(), m.refunds(), m.personalNet(), m.thirdPartyConsumption(),
                        m.thirdPartyReimbursed(), m.thirdPartyPending()));
    }
}
