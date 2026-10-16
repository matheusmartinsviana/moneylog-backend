package com.moneylog.core.purchasegroup.app;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.purchasegroup.app.PurchaseGroupView.Divergence;
import com.moneylog.core.purchasegroup.app.PurchaseGroupView.MemberView;
import com.moneylog.core.purchasegroup.app.PurchaseGroupView.Status;
import com.moneylog.core.purchasegroup.domain.PurchaseGroup;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.transaction.domain.Transaction;

public final class PurchaseGroupViews {

    private PurchaseGroupViews() {
    }

    public static PurchaseGroupView of(Versioned<PurchaseGroup> stored, FinancialRecords records, LocalDate today) {
        return of(stored, records, today, new HashMap<>());
    }

    public static List<PurchaseGroupView> ofAll(List<Versioned<PurchaseGroup>> stored, FinancialRecords records, LocalDate today) {
        Map<UUID, InvoiceChain> chains = new HashMap<>();
        return stored.stream().map(s -> of(s, records, today, chains)).toList();
    }

    private static PurchaseGroupView of(Versioned<PurchaseGroup> stored, FinancialRecords records, LocalDate today,
                                        Map<UUID, InvoiceChain> chains) {
        PurchaseGroup group = stored.value();
        List<PurchasePlan> plans = new ArrayList<>();
        for (UUID transactionId : group.members()) {
            records.transaction(transactionId).ifPresent(t -> plans.add(PurchasePlan.of(t, records, chains, today)));
        }
        Money zero = Money.zero(CurrencyCode.BRL);
        Money total = Money.sum(plans.stream().map(PurchasePlan::total).toList(), CurrencyCode.BRL);
        Money remaining = Money.sum(plans.stream().map(PurchasePlan::remaining).toList(), CurrencyCode.BRL);
        Money myRemaining = Money.sum(plans.stream().map(PurchasePlan::myRemaining).toList(), CurrencyCode.BRL);
        LocalDate lastDueDate = plans.stream().map(PurchasePlan::lastDueDate).filter(d -> d != null).max(Comparator.naturalOrder())
                .orElse(null);
        boolean active = plans.stream().anyMatch(p -> !p.pendingLines().isEmpty());
        Divergence divergence = null;
        if (group.declaredTotal() != null && !group.declaredTotal().equals(total)) {
            divergence = new Divergence(group.declaredTotal(), total, group.declaredTotal().subtract(total));
        }
        List<MemberView> members = plans.stream().map(p -> member(p, zero)).toList();
        return new PurchaseGroupView(group.id(), group.name(), group.interest(), active ? Status.ACTIVE : Status.DONE, total, remaining,
                myRemaining, monthlyInstallment(plans, today), lastDueDate, divergence, members, stored.version(), stored.createdAt(),
                stored.updatedAt());
    }

    private static MemberView member(PurchasePlan plan, Money zero) {
        Transaction t = plan.purchase();
        List<PurchasePlan.Line> pending = plan.pendingLines();
        Money installment = !pending.isEmpty() ? pending.get(0).amount()
                : plan.lines().isEmpty() ? zero : plan.lines().get(plan.lines().size() - 1).amount();
        return new MemberView(t.id(), t.cardId(), plan.cardName(), t.description(), plan.totalInstallments(), pending.size(), installment,
                plan.total(), plan.remaining(), plan.myRemaining(), plan.lastDueDate());
    }

    static Money monthlyInstallment(List<PurchasePlan> plans, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        List<PurchasePlan.Line> pending = plans.stream().flatMap(p -> p.pendingLines().stream()).toList();
        if (pending.isEmpty()) {
            return Money.zero(CurrencyCode.BRL);
        }
        YearMonth target = pending.stream().anyMatch(l -> l.dueMonth().equals(current)) ? current
                : pending.stream().map(PurchasePlan.Line::dueMonth).min(Comparator.naturalOrder()).orElse(current);
        return Money.sum(pending.stream().filter(l -> l.dueMonth().equals(target)).map(PurchasePlan.Line::amount).toList(),
                CurrencyCode.BRL);
    }
}
