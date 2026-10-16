package com.moneylog.infra.income.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moneylog.core.income.domain.DeductionKind;
import com.moneylog.core.income.domain.IncomePreview;
import com.moneylog.core.income.domain.TaxKind;
import com.moneylog.core.sk.money.Money;

public record IncomePreviewResponse(String month, Money gross, Money inss, Money irrf, List<DeductionResponse> fixedDeductions,
                                    List<DeductionResponse> loanDeductions, Money net, PaymentResponse advance, PaymentResponse balance,
                                    PaymentResponse thirteenth, List<TableRefResponse> taxTables) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeductionResponse(DeductionKind kind, String label, Money amount, @JsonInclude(JsonInclude.Include.ALWAYS) UUID debtId,
                                    @JsonInclude(JsonInclude.Include.ALWAYS) Money interestAmount, Money principalAmount,
                                    Confidence confidence, List<String> warnings) {
    }

    public enum Confidence {
        CONFIRMED,
        ESTIMATED
    }

    public record PaymentResponse(LocalDate date, Money amount) {
    }

    public record TableRefResponse(TaxKind kind, int version, LocalDate effectiveFrom) {
    }

    public static IncomePreviewResponse of(IncomePreview p) {
        return new IncomePreviewResponse(p.month().toString(), p.gross(), p.inss(), p.irrf(),
                p.fixedDeductions().stream().map(IncomePreviewResponse::deduction).toList(),
                p.loanDeductions().stream().map(IncomePreviewResponse::deduction).toList(), p.net(), payment(p.advance()),
                payment(p.balance()), payment(p.thirteenth()),
                p.taxTables().stream().map(t -> new TableRefResponse(t.kind(), t.version(), t.effectiveFrom())).toList());
    }

    private static DeductionResponse deduction(IncomePreview.Deduction d) {
        if (d.kind() != DeductionKind.LOAN) {
            return new DeductionResponse(d.kind(), d.label(), d.amount(), d.debtId(), null, null, null, null);
        }
        return new DeductionResponse(d.kind(), d.label(), d.amount(), d.debtId(), d.interestAmount(), d.principalAmount(),
                d.interestNotSplit() ? Confidence.ESTIMATED : Confidence.CONFIRMED, d.warnings());
    }

    private static PaymentResponse payment(IncomePreview.Payment payment) {
        return payment == null ? null : new PaymentResponse(payment.date(), payment.amount());
    }
}
