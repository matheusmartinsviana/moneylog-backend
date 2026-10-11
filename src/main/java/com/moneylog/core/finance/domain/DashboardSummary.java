package com.moneylog.core.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record DashboardSummary(LocalDate asOf, boolean isProjection, Balances balances, Map<Horizon, Money> committed,
                               Map<Horizon, Money> projected, ConsumptionReport month, CashFlowReport cashFlow, NetWorth netWorth) {

    public static final List<Horizon> PROJECTION_HORIZONS = List.of(Horizon.D30, Horizon.D90, Horizon.M6, Horizon.M12);

    public record Balances(Money inAccounts, Money investments, Money available, Money reserved, Money committed30d,
                           InAccountsChange inAccountsChange) {

        public Balances(Money inAccounts, Money investments, Money available, Money reserved, Money committed30d) {
            this(inAccounts, investments, available, reserved, committed30d, null);
        }
    }

    public record InAccountsChange(Money previous, Money amount, BigDecimal percent, LocalDate since) {
    }

    public static DashboardSummary of(FinancialRecords records, Collection<RecurringTransaction> recurrences, LocalDate asOf,
                                      LocalDate today) {
        Ledger ledger = records.ledger();
        CashPosition cash = CashPosition.at(records, ledger, asOf);
        CommitmentSchedule schedule = CommitmentSchedule.of(records, recurrences, asOf);
        Money reserved = GoalReserves.at(records, cash).inCash();
        Map<Horizon, Money> committed = new EnumMap<>(Horizon.class);
        for (Horizon h : Horizon.values()) {
            committed.put(h, schedule.committed(h));
        }
        Map<Horizon, Money> projected = new EnumMap<>(Horizon.class);
        for (Horizon h : PROJECTION_HORIZONS) {
            projected.put(h, cash.inAccounts().add(schedule.incoming(h)).subtract(schedule.committed(h)));
        }
        Money committed30 = committed.get(Horizon.D30);
        Competence competence = Competence.from(asOf);
        Balances balances = new Balances(cash.inAccounts(), cash.investments(),
                cash.inAccounts().subtract(committed30).subtract(reserved), reserved, committed30,
                inAccountsChange(records, ledger, cash, competence, asOf.isAfter(today)));
        return new DashboardSummary(asOf, asOf.isAfter(today), balances, Map.copyOf(committed), Map.copyOf(projected),
                ConsumptionReport.of(records, ledger, competence), CashFlowReport.forCompetence(records, ledger, competence),
                NetWorth.at(ledger, asOf));
    }

    private static InAccountsChange inAccountsChange(FinancialRecords records, Ledger ledger, CashPosition cash, Competence competence,
                                                     boolean projection) {
        if (projection || records.accounts().isEmpty()) {
            return null;
        }
        LocalDate since = competence.minusMonths(1).lastDay();
        Money previous = CashPosition.at(records, ledger, since).inAccounts();
        Money amount = cash.inAccounts().subtract(previous);
        return new InAccountsChange(previous, amount, previous.isZero() ? null : MonthlySummary.ratio(amount, previous.abs()), since);
    }
}
