package com.moneylog.core.finance.app.debts.usecase;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.finance.app.debts.DebtOverviewView;
import com.moneylog.core.finance.app.debts.DebtOverviewView.PayoffGoalRef;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.DebtOverview;
import com.moneylog.core.finance.domain.DebtOverviewCalculator;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalProgress;
import com.moneylog.core.recurrence.domain.MonthlyEstimate;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;

/**
 * Não calcula nada por conta própria: junta o {@link DebtOverviewCalculator} (saldo devedor), o {@link MonthlyEstimate} (custo fixo e renda
 * recorrente) e o {@link DashboardSummary} (em contas e disponível), as mesmas calculadoras das outras telas (PR 01, regra 10).
 */
public class GetDebtOverviewUseCaseImpl implements GetDebtOverviewUseCase {

    private final FinancialRecordsGateway records;
    private final CommitmentSourcesGateway sources;
    private final PersonGateway people;
    private final TimeProvider time;

    public GetDebtOverviewUseCaseImpl(FinancialRecordsGateway records, CommitmentSourcesGateway sources, PersonGateway people,
                                      TimeProvider time) {
        this.records = records;
        this.sources = sources;
        this.people = people;
        this.time = time;
    }

    @Override
    public DebtOverviewView handle(GetDebtOverviewQuery query) {
        FinancialRecords loaded = records.load(query.userId());
        LocalDate today = time.today();
        java.util.Collection<RecurringTransaction> recurrences = sources.recurrences(query.userId());
        DashboardSummary dashboard = DashboardSummary.of(loaded, recurrences, today, today);
        var lastFullMonth = ConsumptionReport.of(loaded, loaded.ledger(), Competence.from(today).minusMonths(1)).income();
        Map<UUID, Person> persons = people.findByIds(query.userId(), loaded.debts().stream().map(Debt::personId)
                .filter(Objects::nonNull).distinct().toList());
        Map<UUID, String> names = new java.util.HashMap<>();
        persons.forEach((id, person) -> names.put(id, person.name()));
        DebtOverview overview = DebtOverviewCalculator.overview(loaded, names, MonthlyEstimate.of(recurrences, today), lastFullMonth,
                dashboard.balances().inAccounts(), dashboard.balances().available(), today, query.months());
        return new DebtOverviewView(overview, payoffGoal(loaded, overview));
    }

    private static PayoffGoalRef payoffGoal(FinancialRecords loaded, DebtOverview overview) {
        return loaded.goals().stream().filter(g -> g.isDebtPayoff() && !g.isArchived()).findFirst().map(g -> {
            GoalProgress progress = g.payoffProgress(overview.total());
            return new PayoffGoalRef(g.id(), g.isBaselineSet(), progress.ratio(), overview.total());
        }).orElse(null);
    }
}
