package com.moneylog.infra.debt.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.debts.DebtOverviewView;
import com.moneylog.core.finance.domain.DebtOverview;
import com.moneylog.core.finance.domain.DebtOverview.ComparisonReason;
import com.moneylog.core.finance.domain.DebtOverview.IncomeSource;
import com.moneylog.core.finance.domain.DebtOverview.PayoffReason;
import com.moneylog.core.sk.money.Money;

/** {@code DebtOverview} do contrato (ADR-013): tudo calculado no backend; o front só exibe. Razões em texto com 4 casas. */
public record DebtOverviewResponse(LocalDate asOf, Money total, Parts parts, Cards cards, DebtGroup people, DebtGroup other,
                                   Money receivablesInfo, boolean hasRecords, FixedCosts fixedCosts, Income income, Liquidity liquidity,
                                   Comparison comparison, Schedule schedule, PayoffGoal payoffGoal, Tracked tracked) {

    public record Parts(Money cards, Money people, Money other) {
    }

    public record Cards(Money total, Money invoiced, Money future, List<CardItem> items) {
    }

    public record CardRef(UUID id, String name) {
    }

    public record CardItem(CardRef card, Money total, Money invoiced, Money future, Money otherCharges, Money adjustment,
                           List<Plan> plans) {
    }

    public record Plan(UUID purchaseId, String description, boolean ongoing, Money installmentAmount, int totalInstallments,
                       int remainingInstallments, int nextInstallmentNumber, Money remaining, LocalDate nextDueDate,
                       LocalDate lastDueDate) {
    }

    public record DebtGroup(Money total, List<DebtItem> items) {
    }

    public record DebtItem(UUID debtId, PersonRefResponse person, String creditorName, String description, Money outstanding,
                           LocalDate expectedDate, boolean overdue) {
    }

    public record FixedCosts(Money monthly, boolean estimate) {
    }

    public record Income(Money monthly, IncomeSource source) {
    }

    public record Liquidity(Money inAccounts, Money available) {
    }

    public record Comparison(String debtToIncome, String fixedCostToIncome, Money debtMinusInAccounts, ComparisonReason reason) {
    }

    public record Schedule(int months, List<Point> points, Money overdue, Money undated, Money beyondHorizon, String payoffCompetence,
                           PayoffReason payoffReason) {
    }

    public record Point(String competence, Money cards, Money people, Money other, Money total, Money remainingAfter) {
    }

    /**
     * Compromissos acompanhados (ADR-014): fora do {@code total}. {@code scheduleByMonth} é o pendente de pagamento direto por mês de
     * vencimento (cronograma do projeto de longo prazo), à parte da curva das dívidas próprias.
     */
    public record Tracked(List<TrackedItem> items, List<TrackedMonth> scheduleByMonth) {
    }

    public record TrackedItem(UUID id, String kind, String name, String counterparty, Money total, Money paid, Money nominalBalance,
                              String progress, Integer installmentsTotal, Integer installmentsPaid, Integer remainingInstallments,
                              LocalDate nextDueDate, Money directPending) {
    }

    public record TrackedMonth(String competence, Money amount) {
    }

    public record PayoffGoal(UUID goalId, boolean baselineSet, String progress, Money remaining) {
    }

    public static DebtOverviewResponse of(DebtOverviewView view) {
        DebtOverview o = view.overview();
        List<CardItem> cards = o.cards().items().stream().map(c -> new CardItem(new CardRef(c.cardId(), c.cardName()), c.total(),
                c.invoiced(), c.future(), c.otherCharges(), c.adjustment(),
                c.plans().stream().map(p -> new Plan(p.purchaseId(), p.description(), p.ongoing(), p.installmentAmount(),
                        p.totalInstallments(), p.remainingInstallments(), p.nextInstallmentNumber(), p.remaining(), p.nextDueDate(),
                        p.lastDueDate())).toList())).toList();
        DebtOverview.Schedule s = o.schedule();
        return new DebtOverviewResponse(o.asOf(), o.total(), new Parts(o.parts().cards(), o.parts().people(), o.parts().other()),
                new Cards(o.cards().total(), o.cards().invoiced(), o.cards().future(), cards), group(o.people()), group(o.other()),
                o.receivablesInfo(), o.hasRecords(), new FixedCosts(o.fixedCostsMonthly(), true),
                new Income(o.income().monthly(), o.income().source()), new Liquidity(o.liquidity().inAccounts(), o.liquidity().available()),
                new Comparison(o.comparison().debtToIncome() == null ? null : o.comparison().debtToIncome().toPlainString(),
                        o.comparison().fixedCostToIncome() == null ? null : o.comparison().fixedCostToIncome().toPlainString(),
                        o.comparison().debtMinusInAccounts(), o.comparison().reason()),
                new Schedule(s.months(), s.points().stream().map(p -> new Point(p.competence().toString(), p.cards(), p.people(), p.other(),
                        p.total(), p.remainingAfter())).toList(), s.overdue(), s.undated(), s.beyondHorizon(),
                        s.payoffCompetence() == null ? null : s.payoffCompetence().toString(), s.payoffReason()),
                view.payoffGoal() == null ? null : new PayoffGoal(view.payoffGoal().goalId(), view.payoffGoal().baselineSet(),
                        view.payoffGoal().progress().toPlainString(), view.payoffGoal().remaining()),
                tracked(view));
    }

    private static Tracked tracked(DebtOverviewView view) {
        java.util.Map<java.time.YearMonth, Money> byMonth = new java.util.TreeMap<>();
        List<TrackedItem> items = view.tracked().stream().map(c -> {
            c.pendingByMonth().forEach((m, a) -> byMonth.merge(m, a, Money::add));
            boolean project = c.kind() == com.moneylog.core.commitment.domain.CommitmentKind.LONG_TERM_PROJECT;
            return new TrackedItem(c.id(), c.kind().name(), c.name(), c.counterparty(), c.nominalTotal(), c.paid(), c.nominalBalance(),
                    c.progress() == null ? null : c.progress().toPlainString(), c.installmentsTotal(), c.installmentsPaid(),
                    c.remainingInstallments(), c.nextDueDate(), project ? c.directPending() : null);
        }).toList();
        return new Tracked(items, byMonth.entrySet().stream().map(e -> new TrackedMonth(e.getKey().toString(), e.getValue())).toList());
    }

    private static DebtGroup group(DebtOverview.DebtGroup g) {
        return new DebtGroup(g.total(), g.items().stream().map(i -> new DebtItem(i.debtId(),
                i.personId() == null ? null : new PersonRefResponse(i.personId(), i.creditorName()), i.creditorName(), i.description(),
                i.outstanding(), i.expectedDate(), i.overdue())).toList());
    }
}
