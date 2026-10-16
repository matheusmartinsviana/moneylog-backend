package com.moneylog.core.finance.app.simulation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.card.domain.InstallmentPlan;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.commitment.domain.CommitmentKind;
import com.moneylog.core.commitment.domain.ScheduleItem;
import com.moneylog.core.commitment.domain.ScheduleItemType;
import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.finance.app.statement.GetOwnerStatementUseCase;
import com.moneylog.core.finance.app.statement.GetOwnerStatementUseCase.GetOwnerStatementQuery;
import com.moneylog.core.finance.domain.OwnerIncome;
import com.moneylog.core.finance.domain.OwnerStatement;
import com.moneylog.core.finance.domain.PurchaseClassifier;
import com.moneylog.core.finance.domain.PurchaseSimulation;
import com.moneylog.core.finance.domain.PurchaseSimulation.Horizon;
import com.moneylog.core.finance.domain.PurchaseSimulation.MonthImpact;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.SubscriptionCost;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.CardCashOutPlan;
import com.moneylog.core.transaction.domain.TransactionType;

public class SimulatePurchaseUseCaseImpl implements SimulatePurchaseUseCase {

    public static final int MAX_RATE_BPS = CardCashOutPlan.MAX_RATE_BPS;
    public static final int MAX_CANCELLED = 50;

    private final GetOwnerStatementUseCase statement;
    private final RecurrenceGateway recurrences;
    private final CommitmentGateway commitments;
    private final TimeProvider time;
    private final int calculatedMonths;

    public SimulatePurchaseUseCaseImpl(GetOwnerStatementUseCase statement, RecurrenceGateway recurrences, CommitmentGateway commitments,
                                       TimeProvider time, int calculatedMonths) {
        this.statement = statement;
        this.recurrences = recurrences;
        this.commitments = commitments;
        this.time = time;
        this.calculatedMonths = Math.max(1, Math.min(calculatedMonths, OwnerStatement.MAX_MONTHS));
    }

    @Override
    public PurchaseSimulation handle(SimulatePurchaseCommand c) {
        int rate = c.interestRatePerMonthBps() == null ? 0 : c.interestRatePerMonthBps();
        validate(c, rate);
        LocalDate today = time.today();
        Competence first = c.firstInstallmentMonth();
        CardCashOutPlan plan = CardCashOutPlan.of(c.amount(), c.installments(), null, rate);
        List<Money> parcels = plan.installments();
        int calculated = Math.min(c.installments(), calculatedMonths);
        Horizon horizon = new Horizon(first, first.plusMonths(calculated - 1L), calculated, c.installments(), c.installments() > calculated);
        OwnerStatement base = statement.handle(new GetOwnerStatementQuery(c.userId(), first, calculated, today));
        boolean withCuts = !c.cancelSubscriptionIds().isEmpty();
        Money cutsPerMonth = withCuts ? cutsMonthly(c, today) : null;
        Competence current = Competence.from(today);
        Money zero = Money.zero(CurrencyCode.BRL);
        List<MonthImpact> months = new ArrayList<>();
        Money cumulative = zero;
        Money cumulativeCuts = zero;
        Money minEnd = null;
        Money minEndWithCuts = null;
        for (int i = 0; i < calculated; i++) {
            OwnerStatement.MonthColumn column = base.months().get(i);
            Money parcel = parcels.get(i);
            Money after = column.surplus().subtract(parcel);
            Money cuts = withCuts ? (column.competence().isBefore(current) ? zero : cutsPerMonth) : null;
            Money afterWithCuts = withCuts ? after.add(cuts) : null;
            Money estimatedIncome = estimatedIncome(column.income());
            cumulative = cumulative.add(parcel);
            Money end = column.endBalance().subtract(cumulative);
            minEnd = minEnd == null || end.isLessThan(minEnd) ? end : minEnd;
            if (withCuts) {
                cumulativeCuts = cumulativeCuts.add(cuts);
                Money endWithCuts = end.add(cumulativeCuts);
                minEndWithCuts = minEndWithCuts == null || endWithCuts.isLessThan(minEndWithCuts) ? endWithCuts : minEndWithCuts;
            }
            months.add(new MonthImpact(column.competence(), column.surplus(), after, after.subtract(column.surplus()), after.isNegative(),
                    cuts, afterWithCuts, withCuts ? afterWithCuts.isNegative() : null,
                    estimatedIncome.isPositive() ? OwnerIncome.Confidence.ESTIMATED : OwnerIncome.Confidence.CONFIRMED,
                    !after.isNegative() && after.subtract(estimatedIncome).isNegative()));
        }
        boolean financing = financingPending(c.userId());
        var verdict = PurchaseClassifier.classify(months, horizon, financing);
        return new PurchaseSimulation(parcels.get(0), plan.total(), months, horizon, verdict.classification(), verdict.reasons(), minEnd,
                minEndWithCuts, financing);
    }

    private void validate(SimulatePurchaseCommand c, int rate) {
        if (c.amount() == null || !c.amount().isPositive() || !CurrencyCode.BRL.equals(c.amount().currency())) {
            throw new SimulationInvalidException("o valor da compra precisa ser maior que zero, em BRL.");
        }
        AmountLimit.ensureWithinLimit(c.amount(), "amount");
        if (c.installments() < InstallmentPlan.MIN_INSTALLMENTS || c.installments() > InstallmentPlan.MAX_INSTALLMENTS) {
            throw new SimulationInvalidException("o número de parcelas vai de " + InstallmentPlan.MIN_INSTALLMENTS + " a "
                    + InstallmentPlan.MAX_INSTALLMENTS + ".");
        }
        if (c.amount().cents() < c.installments()) {
            throw new SimulationInvalidException("o valor em " + c.installments() + " parcelas deixaria parcela menor que 1 centavo.");
        }
        if (rate < 0 || rate > MAX_RATE_BPS) {
            throw new SimulationInvalidException("os juros ao mês vão de 0 a " + MAX_RATE_BPS + " pontos-base.");
        }
        Competence first = c.firstInstallmentMonth();
        if (first == null || !FinancialDates.isInWindow(first.firstDay())
                || !FinancialDates.isInWindow(first.plusMonths(Math.max(calculatedMonths, 1) - 1L).lastDay())) {
            throw new SimulationInvalidException("o mês da primeira parcela está fora da janela de 2000-01 a 2100-12.");
        }
        if (c.cancelSubscriptionIds().size() > MAX_CANCELLED) {
            throw new SimulationInvalidException("no máximo " + MAX_CANCELLED + " assinaturas para cortar.");
        }
    }

    private Money cutsMonthly(SimulatePurchaseCommand c, LocalDate today) {
        Set<UUID> seen = new HashSet<>();
        List<SubscriptionCost.Monthly> parts = new ArrayList<>();
        for (UUID id : c.cancelSubscriptionIds()) {
            if (!seen.add(id)) {
                throw new SimulationInvalidException("assinatura repetida nos cortes.");
            }
            RecurringTransaction recurrence = recurrences.findById(c.userId(), id).orElseThrow(() -> new RecurrenceNotFoundException(id));
            boolean active = recurrence.isActive() && recurrence.isSubscription() && recurrence.type() != TransactionType.INCOME
                    && !recurrence.isEnded(today);
            if (!active) {
                throw new SimulationInvalidException("só assinaturas ativas podem ser cortadas.");
            }
            parts.add(SubscriptionCost.mine(recurrence));
        }
        return SubscriptionCost.sum(parts).money();
    }

    private boolean financingPending(UUID userId) {
        for (TrackedCommitment commitment : commitments.findAll(userId)) {
            if (commitment.kind() != CommitmentKind.LONG_TERM_PROJECT) {
                continue;
            }
            for (ScheduleItem item : commitment.schedule()) {
                if (item.type() == ScheduleItemType.FINANCING && !item.isPaid()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Money estimatedIncome(OwnerIncome income) {
        return Money.sum(income.items().stream().filter(i -> i.confidence() == OwnerIncome.Confidence.ESTIMATED)
                .map(OwnerIncome.Item::amount).toList(), CurrencyCode.BRL);
    }
}
