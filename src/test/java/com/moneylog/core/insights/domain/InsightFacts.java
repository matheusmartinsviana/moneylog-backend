package com.moneylog.core.insights.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

/** Construtor de {@link MonthFacts} e {@link DayFacts} com dados 100% sintéticos para os testes dos calculadores. */
final class InsightFacts {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    static final Competence OCT = Competence.of(2026, 10);

    private InsightFacts() {
    }

    static Money brl(String amount) {
        return Money.of(amount, CurrencyCode.BRL);
    }

    static MonthSnapshot snapshot(Competence month, String income, String consumption) {
        return new MonthSnapshot(month, brl(income), brl(consumption), brl("0.00"), Map.of());
    }

    static MonthSnapshot snapshot(Competence month, String income, String consumption, String essential) {
        return new MonthSnapshot(month, brl(income), brl(consumption), brl(essential), Map.of());
    }

    static MonthSnapshot snapshot(Competence month, String income, String consumption, Map<UUID, Money> byCategory) {
        return new MonthSnapshot(month, brl(income), brl(consumption), brl("0.00"), byCategory);
    }

    /** {@code n} meses fechados imediatamente antes de {@code month}, do mais antigo ao mais recente, com a renda e o consumo dados. */
    static List<MonthSnapshot> closed(Competence month, int n, String income, String consumption) {
        List<MonthSnapshot> list = new ArrayList<>();
        for (int back = n; back >= 1; back--) {
            list.add(snapshot(month.minusMonths(back), income, consumption));
        }
        return list;
    }

    static Builder month(Competence month, LocalDate today) {
        return new Builder(month, today);
    }

    /** Mês passado fechado: hoje é depois do mês. */
    static Builder closedMonth(Competence month) {
        return new Builder(month, month.plusMonths(2).firstDay());
    }

    static final class Builder {
        final Competence month;
        final LocalDate today;
        MonthSnapshot current;
        List<MonthSnapshot> history = List.of();
        MonthFacts.Budget budget;
        List<Money> dailyVariable = List.of();
        Money recurringOut = brl("0.00");
        Money installments = brl("0.00");
        Money debtPaymentsDue = brl("0.00");
        Money debtTotal = brl("0.00");
        Money cashBalance = brl("0.00");
        boolean hasCash = true;
        List<Money> installmentLoad = List.of();
        List<MonthFacts.GoalFact> goals = List.of();
        List<MonthFacts.CardFact> cards = List.of();
        Money cardOutstanding = brl("0.00");
        Map<UUID, String> names = new HashMap<>();
        List<SubscriptionDetector.Line> subscriptions = List.of();
        Set<String> registered = Set.of();

        Builder(Competence month, LocalDate today) {
            this.month = month;
            this.today = today;
            this.current = snapshot(month, "0.00", "0.00");
        }

        Builder current(MonthSnapshot value) {
            this.current = value;
            return this;
        }

        Builder history(List<MonthSnapshot> value) {
            this.history = value;
            return this;
        }

        Builder budget(String received, String spent, String later, String pending, String reserved) {
            this.budget = new MonthFacts.Budget(brl(received), brl(spent), brl(later), brl(pending), brl(reserved));
            return this;
        }

        Builder daily(String... amounts) {
            List<Money> list = new ArrayList<>();
            for (String a : amounts) {
                list.add(brl(a));
            }
            this.dailyVariable = list;
            return this;
        }

        Builder recurringOut(String v) {
            this.recurringOut = brl(v);
            return this;
        }

        Builder installments(String v) {
            this.installments = brl(v);
            return this;
        }

        Builder debt(String payments, String total) {
            this.debtPaymentsDue = brl(payments);
            this.debtTotal = brl(total);
            return this;
        }

        Builder cash(String v) {
            this.cashBalance = brl(v);
            return this;
        }

        Builder load(String... amounts) {
            List<Money> list = new ArrayList<>();
            for (String a : amounts) {
                list.add(brl(a));
            }
            this.installmentLoad = list;
            return this;
        }

        Builder goals(MonthFacts.GoalFact... facts) {
            this.goals = List.of(facts);
            return this;
        }

        Builder cards(MonthFacts.CardFact... facts) {
            this.cards = List.of(facts);
            return this;
        }

        MonthFacts build() {
            return new MonthFacts(today, month, current, history, budget, dailyVariable, recurringOut, installments, debtPaymentsDue, debtTotal,
                    cashBalance, hasCash, installmentLoad, goals, cards, cardOutstanding, names, subscriptions, registered);
        }

        InsightSink run() {
            InsightSink sink = new InsightSink();
            MonthlyInsights.compute(build(), sink);
            return sink;
        }
    }

    static Insight only(InsightSink sink, String type) {
        List<Insight> found = sink.insights().stream().filter(i -> i.type().equals(type)).toList();
        if (found.size() != 1) {
            throw new AssertionError("esperava 1 insight " + type + " mas vieram " + found.size() + ": " + sink.insights());
        }
        return found.get(0);
    }

    static boolean has(InsightSink sink, String type) {
        return sink.insights().stream().anyMatch(i -> i.type().equals(type));
    }

    static boolean insufficient(InsightSink sink, String type, InsufficientData.Reason reason) {
        return sink.insufficient().stream().anyMatch(d -> d.type().equals(type) && d.reason() == reason);
    }
}
