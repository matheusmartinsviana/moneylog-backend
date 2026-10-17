package com.moneylog.core.purchasegroup.app;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record PurchaseCommitments(Competence until, Money committed, Money committedMine, List<Month> byMonth) {

    public record Month(Competence competence, Money amount, Money mine) {
    }

    public PurchaseCommitments {
        byMonth = List.copyOf(byMonth);
    }

    public static PurchaseCommitments of(FinancialRecords records, Competence until, LocalDate today) {
        YearMonth first = YearMonth.from(today);
        Map<YearMonth, Money[]> months = new TreeMap<>();
        for (YearMonth m = first; !m.isAfter(until.value()); m = m.plusMonths(1)) {
            months.put(m, new Money[] {Money.zero(CurrencyCode.BRL), Money.zero(CurrencyCode.BRL)});
        }
        for (PurchasePlan plan : PurchasePlan.allOf(records, today)) {
            for (PurchasePlan.Line line : plan.pendingLines()) {
                YearMonth bucket = line.dueMonth().isBefore(first) ? first : line.dueMonth();
                Money[] sums = months.get(bucket);
                if (sums != null) {
                    sums[0] = sums[0].add(line.amount());
                    sums[1] = sums[1].add(line.mine());
                }
            }
        }
        List<Month> byMonth = new ArrayList<>();
        Money committed = Money.zero(CurrencyCode.BRL);
        Money mine = Money.zero(CurrencyCode.BRL);
        for (Map.Entry<YearMonth, Money[]> e : months.entrySet()) {
            byMonth.add(new Month(new Competence(e.getKey()), e.getValue()[0], e.getValue()[1]));
            committed = committed.add(e.getValue()[0]);
            mine = mine.add(e.getValue()[1]);
        }
        return new PurchaseCommitments(until, committed, mine, byMonth);
    }
}
