package com.moneylog.infra.statement.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.domain.OwnerIncome;
import com.moneylog.core.finance.domain.OwnerOutflow;
import com.moneylog.core.finance.domain.OwnerStatement;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;

public record OwnerStatementResponse(LocalDate asOf, String view, int incomeLagMonths, Money openingBalance, List<MonthResponse> months,
                                     List<String> negativeMonths, List<WarningResponse> warnings) {

    public record IncomeItemResponse(OwnerIncome.Source source, UUID refId, LocalDate date, Money amount, OwnerIncome.Status status,
                                     OwnerIncome.Confidence confidence) {
    }

    public record IncomeResponse(Money total, Money receivedInAccount, Money appliedToDebt, List<IncomeItemResponse> items) {
    }

    public record MethodAmountResponse(PaymentMethod method, Money amount) {
    }

    public record OffCardResponse(Money fixed, Money variable, List<MethodAmountResponse> byMethod) {
    }

    public record OutflowResponse(Money cardsMine, Money cardsGross, Money subscriptionsMine, OffCardResponse offCard, Money realEstateMine,
                                  Money total) {
    }

    public record MonthResponse(String competence, IncomeResponse income, OutflowResponse outflow, Money companyReimbursable, Money surplus,
                                Money endBalance, boolean negative, Money receivablesPending) {
    }

    public record WarningResponse(String code, String competence) {
    }

    public static OwnerStatementResponse of(OwnerStatement s) {
        return new OwnerStatementResponse(s.asOf(), "MINE", s.incomeLagMonths(), s.openingBalance(),
                s.months().stream().map(OwnerStatementResponse::month).toList(),
                s.negativeMonths().stream().map(Object::toString).toList(),
                s.warnings().stream().map(w -> new WarningResponse(w.code(), w.competence() == null ? null : w.competence().toString()))
                        .toList());
    }

    private static MonthResponse month(OwnerStatement.MonthColumn m) {
        return new MonthResponse(m.competence().toString(), income(m.income()), outflow(m.outflow()), m.companyReimbursable(), m.surplus(),
                m.endBalance(), m.negative(), m.receivablesPending());
    }

    private static IncomeResponse income(OwnerIncome income) {
        return new IncomeResponse(income.total(), income.receivedInAccount(), income.appliedToDebt(),
                income.items().stream().map(i -> new IncomeItemResponse(i.source(), i.refId(), i.date(), i.amount(), i.status(),
                        i.confidence())).toList());
    }

    private static OutflowResponse outflow(OwnerOutflow o) {
        return new OutflowResponse(o.cardsMine(), o.cardsGross(), o.subscriptionsMine(),
                new OffCardResponse(o.offCardFixed(), o.offCardVariable(),
                        o.byMethod().stream().map(b -> new MethodAmountResponse(b.method(), b.amount())).toList()),
                o.realEstateMine(), o.total());
    }
}
