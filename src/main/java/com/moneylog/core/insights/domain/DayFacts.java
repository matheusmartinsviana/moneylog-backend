package com.moneylog.core.insights.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record DayFacts(LocalDate date, MonthFacts.Budget budget, List<Money> variableBefore, Money variableToday, Money cashBalance,
                       boolean hasCashAccounts, List<Due> dueWeek, List<CashEvent> events, List<TodayEntry> todayEntries,
                       Map<UUID, List<Money>> categoryHistory, Map<UUID, String> categoryNames, List<Closing> closings) {

    public DayFacts {
        Objects.requireNonNull(date, "date");
        variableBefore = List.copyOf(variableBefore);
        dueWeek = List.copyOf(dueWeek);
        events = List.copyOf(events);
        todayEntries = List.copyOf(todayEntries);
        categoryHistory = Map.copyOf(categoryHistory);
        categoryNames = Map.copyOf(categoryNames);
        closings = List.copyOf(closings);
    }

    public record Due(LocalDate dueDate, Money amount, String ref) {
    }

    public record CashEvent(LocalDate date, Money signedAmount) {
    }

    public record TodayEntry(String subjectId, UUID categoryId, Money amount) {
    }

    public record Closing(String ref, String cardName, LocalDate closingDate, Money partial) {
    }

    public int dayOfMonth() {
        return date.getDayOfMonth();
    }

    public int daysInMonth() {
        return date.lengthOfMonth();
    }
}
