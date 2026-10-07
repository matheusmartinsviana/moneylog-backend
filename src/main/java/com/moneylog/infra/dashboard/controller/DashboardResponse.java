package com.moneylog.infra.dashboard.controller;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.moneylog.core.finance.app.dashboard.DashboardView;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.Horizon;
import com.moneylog.core.finance.domain.NetWorth;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;

/** {@code DashboardSummary} do contrato: tudo calculado pelo backend (D2 §4.4), com {@code hasAccounts} (S-61). */
public record DashboardResponse(LocalDate asOf, @JsonProperty("isProjection") boolean isProjection, boolean hasAccounts,
                                Balances balances, Committed committed, Projected projected, Month month, NetWorthResponse netWorth) {

    /** {@code inAccountsChange} só existe fora de projeção e com ao menos uma conta (redesign R4); ausente, não {@code null}. */
    public record Balances(Money inAccounts, Money investments, Money available, Money reserved, Money committed30d,
                           @JsonInclude(JsonInclude.Include.NON_NULL) InAccountsChange inAccountsChange) {
    }

    public record InAccountsChange(Money previous, Money amount, String percent, LocalDate since) {
    }

    public record Committed(Money d7, Money d30, Money d60, Money d90, Money m6, Money m12) {
    }

    public record Projected(Money d30, Money d90, Money m6, Money m12) {
    }

    public record CashFlow(Money in, Money out, Money net) {
    }

    public record ExpensesPaid(Money total, Map<String, Money> byMethod) {
    }

    public record Month(String competence, Money income, ExpensesPaid expensesPaid, Money credit, Money refunds, Money consumption,
                        CashFlow cashFlow, Money investedNet, Money lent, Money received, Money net, Money debtConsumption) {
    }

    public record NetWorthResponse(Money accounts, Money receivables, Money cardLiabilities, Money payables, Money total,
                                   Money cardFutureInstallments) {
    }

    public static DashboardResponse of(DashboardView view) {
        DashboardSummary s = view.summary();
        DashboardSummary.Balances b = s.balances();
        Map<Horizon, Money> c = s.committed();
        Map<Horizon, Money> p = s.projected();
        ConsumptionReport m = s.month();
        Map<String, Money> byMethod = new LinkedHashMap<>();
        for (PaymentMethod method : PaymentMethod.values()) {
            byMethod.put(method.name(), m.expensesByMethod().get(method));
        }
        NetWorth n = s.netWorth();
        return new DashboardResponse(s.asOf(), s.isProjection(), view.hasAccounts(),
                new Balances(b.inAccounts(), b.investments(), b.available(), b.reserved(), b.committed30d(), change(b.inAccountsChange())),
                new Committed(c.get(Horizon.D7), c.get(Horizon.D30), c.get(Horizon.D60), c.get(Horizon.D90), c.get(Horizon.M6), c.get(Horizon.M12)),
                new Projected(p.get(Horizon.D30), p.get(Horizon.D90), p.get(Horizon.M6), p.get(Horizon.M12)),
                new Month(m.competence().toString(), m.income(), new ExpensesPaid(m.expensesPaid(), byMethod), m.credit(), m.refunds(),
                        m.consumption(), new CashFlow(s.cashFlow().in(), s.cashFlow().out(), s.cashFlow().net()), m.investedNet(), m.lent(),
                        m.received(), m.net(), m.debtConsumption()),
                new NetWorthResponse(n.accounts(), n.receivables(), n.cardLiabilities(), n.payables(), n.total(), n.cardFutureInstallments()));
    }

    private static InAccountsChange change(DashboardSummary.InAccountsChange c) {
        return c == null ? null : new InAccountsChange(c.previous(), c.amount(), c.percent() == null ? null : c.percent().toPlainString(), c.since());
    }
}
