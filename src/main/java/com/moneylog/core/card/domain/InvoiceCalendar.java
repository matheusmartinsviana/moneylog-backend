package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.moneylog.core.card.exception.CardExceptions.CardFieldRequiredException;

public final class InvoiceCalendar {

    private final List<BillingRule> rules;

    public InvoiceCalendar(List<BillingRule> rules) {
        Objects.requireNonNull(rules, "rules");
        if (rules.isEmpty()) {
            throw new CardFieldRequiredException("billingRules");
        }
        this.rules = rules.stream().sorted(Comparator.comparing(BillingRule::effectiveFrom)).toList();
    }

    public InvoicePeriod periodFor(LocalDate date) {
        YearMonth month = YearMonth.from(date);
        return date.isBefore(closingIn(month)) ? periodClosingIn(month) : periodClosingIn(month.plusMonths(1));
    }

    public InvoicePeriod periodClosingIn(YearMonth month) {
        BillingRule rule = ruleFor(month);
        LocalDate closing = rule.closingDay().applyTo(month);
        LocalDate due = rule.dueDay().applyTo(rule.dueInSameMonth() ? month : month.plusMonths(1));
        if (!due.isAfter(closing)) {
            due = rule.dueDay().applyTo(month.plusMonths(1));
        }
        return new InvoicePeriod(closingIn(month.minusMonths(1)), closing, due, YearMonth.from(due));
    }

    public InvoicePeriod plusCycles(InvoicePeriod period, int k) {
        return periodClosingIn(period.closingMonth().plusMonths(k));
    }

    public InvoicePeriod next(InvoicePeriod period) {
        return plusCycles(period, 1);
    }

    public boolean isClosingDate(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return closingIn(YearMonth.from(date)).equals(date);
    }

    public BillingRule ruleFor(YearMonth month) {
        BillingRule current = rules.getFirst();
        for (BillingRule rule : rules) {
            if (!rule.effectiveFrom().isAfter(month)) {
                current = rule;
            }
        }
        return current;
    }

    public List<BillingRule> rules() {
        return rules;
    }

    private LocalDate closingIn(YearMonth month) {
        return ruleFor(month).closingDay().applyTo(month);
    }
}
