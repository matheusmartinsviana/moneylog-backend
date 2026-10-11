package com.moneylog.core.insights.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record MonthFacts(LocalDate today, Competence month, MonthSnapshot current, List<MonthSnapshot> history, Budget budget,
                         List<Money> dailyVariable, Money recurringOut, Money installments, Money debtPaymentsDue, Money debtTotal,
                         Money cashBalance, boolean hasCashAccounts, List<Money> installmentLoad, List<GoalFact> goals,
                         List<CardFact> cards, Money cardOutstanding, Map<UUID, String> categoryNames,
                         List<SubscriptionDetector.Line> subscriptionLines, Set<String> registeredDescriptions) {

    public MonthFacts {
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(current, "current");
        history = List.copyOf(history);
        dailyVariable = List.copyOf(dailyVariable);
        installmentLoad = List.copyOf(installmentLoad);
        goals = List.copyOf(goals);
        cards = List.copyOf(cards);
        categoryNames = Map.copyOf(categoryNames);
        subscriptionLines = List.copyOf(subscriptionLines);
        registeredDescriptions = Set.copyOf(registeredDescriptions);
    }

    public record Budget(Money received, Money spentSoFar, Money laterKnown, Money pendingRecurring, Money reserved) {

        public Money upcoming() {
            return laterKnown.add(pendingRecurring);
        }

        public Money free() {
            return received.subtract(spentSoFar).subtract(upcoming()).subtract(reserved);
        }
    }

    public record GoalFact(UUID goalId, String name, boolean hasPlan, boolean onTrack, Money requiredPace, Money gap, int monthsLeft,
                           int streak) {
    }

    public record CardFact(UUID cardId, String name, Money limit, Money used) {
    }

    public boolean currentMonth() {
        return month.equals(Competence.from(today));
    }

    public boolean futureMonth() {
        return month.isAfter(Competence.from(today));
    }

    public int daysInMonth() {
        return month.value().lengthOfMonth();
    }

    public int daysElapsed() {
        if (futureMonth()) {
            return 0;
        }
        return currentMonth() ? today.getDayOfMonth() : daysInMonth();
    }
}
