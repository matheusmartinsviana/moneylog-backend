package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.card.exception.CardExceptions.CardAlreadyArchivedException;
import com.moneylog.core.card.exception.CardExceptions.CardArchivedException;
import com.moneylog.core.card.exception.CardExceptions.CardFieldRequiredException;
import com.moneylog.core.card.exception.CardExceptions.CardInstitutionTooLongException;
import com.moneylog.core.card.exception.CardExceptions.CardNotArchivedException;
import com.moneylog.core.card.exception.CardExceptions.CardNameRequiredException;
import com.moneylog.core.card.exception.CardExceptions.CardNameTooLongException;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.card.exception.CardExceptions.InvalidBillingDayException;
import com.moneylog.core.card.exception.CardExceptions.InvalidCardLimitException;
import com.moneylog.core.card.exception.CardExceptions.StaleBillingChangeException;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.DayOfMonthRule;

public final class CreditCard {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int INSTITUTION_MAX_LENGTH = 100;

    private final UUID id;
    private final UUID userId;
    private String name;
    private String institution;
    private Money limit;
    private UUID defaultPaymentAccountId;
    private final List<BillingRule> rules;
    private CardStatus status;
    private final long version;
    private boolean closingDayConfirmed = true;

    private CreditCard(UUID id, UUID userId, String name, String institution, Money limit, UUID defaultPaymentAccountId,
                       List<BillingRule> rules, CardStatus status, long version) {
        this.id = required(id, "id");
        this.userId = required(userId, "userId");
        this.name = requireName(name);
        this.institution = requireInstitution(institution);
        this.limit = requireLimit(limit);
        this.defaultPaymentAccountId = defaultPaymentAccountId;
        if (rules == null || rules.isEmpty()) {
            throw new CardFieldRequiredException("billingRules");
        }
        this.rules = new ArrayList<>(new InvoiceCalendar(rules).rules());
        this.status = required(status, "status");
        this.version = version;
    }

    public static CreditCard create(UUID id, UUID userId, String name, String institution, Money limit, int closingDay,
                                    int dueDay, UUID defaultPaymentAccountId, LocalDate createdOn) {
        BillingRule rule = rule(YearMonth.from(required(createdOn, "createdOn")), closingDay, dueDay);
        return new CreditCard(id, userId, name, institution, limit, defaultPaymentAccountId, List.of(rule), CardStatus.ACTIVE, 0L);
    }

    public static CreditCard restore(UUID id, UUID userId, String name, String institution, Money limit,
                                     UUID defaultPaymentAccountId, List<BillingRule> rules, CardStatus status, long version) {
        return new CreditCard(id, userId, name, institution, limit, defaultPaymentAccountId, rules, status, version);
    }

    public static CreditCard restore(UUID id, UUID userId, String name, String institution, Money limit,
                                     UUID defaultPaymentAccountId, List<BillingRule> rules, CardStatus status, long version,
                                     boolean closingDayConfirmed) {
        CreditCard card = new CreditCard(id, userId, name, institution, limit, defaultPaymentAccountId, rules, status, version);
        card.closingDayConfirmed = closingDayConfirmed;
        return card;
    }

    public void markClosingDayConfirmed(boolean confirmed) {
        this.closingDayConfirmed = confirmed;
    }

    public boolean closingDayConfirmed() {
        return closingDayConfirmed;
    }

    public InvoiceCalendar calendar() {
        return new InvoiceCalendar(rules);
    }

    public BillingChange planBillingChange(int newClosingDay, int newDueDay, LocalDate today) {
        InvoiceCalendar before = calendar();
        YearMonth from = before.periodFor(required(today, "today")).closingMonth().plusMonths(1);
        BillingRule newRule = rule(from, newClosingDay, newDueDay);
        List<BillingRule> after = new ArrayList<>(rules.stream().filter(r -> r.effectiveFrom().isBefore(from)).toList());
        after.add(newRule);
        return new BillingChange(id, from, before, new InvoiceCalendar(after));
    }

    public void applyBillingChange(BillingChange change) {
        required(change, "change");
        if (!id.equals(change.cardId()) || !change.before().rules().equals(calendar().rules())) {
            throw new StaleBillingChangeException(id);
        }
        rules.clear();
        rules.addAll(change.after().rules());
    }

    public BillingChange changeBillingDays(int newClosingDay, int newDueDay, LocalDate today) {
        BillingChange change = planBillingChange(newClosingDay, newDueDay, today);
        applyBillingChange(change);
        return change;
    }

    public void updateDetails(String newName, String newInstitution, Money newLimit, UUID newDefaultPaymentAccountId) {
        String validName = requireName(newName);
        String validInstitution = requireInstitution(newInstitution);
        Money validLimit = requireLimit(newLimit);
        this.name = validName;
        this.institution = validInstitution;
        this.limit = validLimit;
        this.defaultPaymentAccountId = newDefaultPaymentAccountId;
    }

    public void archive() {
        if (status == CardStatus.ARCHIVED) {
            throw new CardAlreadyArchivedException();
        }
        this.status = CardStatus.ARCHIVED;
    }

    public void unarchive() {
        if (status != CardStatus.ARCHIVED) {
            throw new CardNotArchivedException();
        }
        this.status = CardStatus.ACTIVE;
    }

    public void ensureCanReceiveTransactions() {
        if (status == CardStatus.ARCHIVED) {
            throw new CardArchivedException();
        }
    }

    public void ensureBelongsTo(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new CardNotFoundException(id);
        }
    }

    public BookRef book() {
        return BookRef.card(id);
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public String name() {
        return name;
    }

    public String institution() {
        return institution;
    }

    public Money limit() {
        return limit;
    }

    public CurrencyCode currency() {
        return limit == null ? CurrencyCode.BRL : limit.currency();
    }

    public UUID defaultPaymentAccountId() {
        return defaultPaymentAccountId;
    }

    public int closingDay() {
        return rules.getLast().closingDay().day();
    }

    public int dueDay() {
        return rules.getLast().dueDay().day();
    }

    public List<BillingRule> billingRules() {
        return List.copyOf(rules);
    }

    public CardStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    private static BillingRule rule(YearMonth from, int closingDay, int dueDay) {
        return new BillingRule(from, day("fechamento", closingDay), day("vencimento", dueDay));
    }

    private static DayOfMonthRule day(String field, int day) {
        if (day < 1 || day > 31) {
            throw new InvalidBillingDayException(field, day);
        }
        return DayOfMonthRule.of(day);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new CardNameRequiredException();
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new CardNameTooLongException(NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    private static String requireInstitution(String institution) {
        if (institution == null || institution.isBlank()) {
            return null;
        }
        String trimmed = institution.strip();
        if (trimmed.length() > INSTITUTION_MAX_LENGTH) {
            throw new CardInstitutionTooLongException(INSTITUTION_MAX_LENGTH);
        }
        return trimmed;
    }

    private static Money requireLimit(Money limit) {
        if (limit == null) {
            return null;
        }
        if (!limit.isPositive() || !CurrencyCode.BRL.equals(limit.currency())) {
            throw new InvalidCardLimitException();
        }
        return AmountLimit.ensureWithinLimit(limit, "limit");
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new CardFieldRequiredException(field);
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CreditCard other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "CreditCard[" + id + ", " + name + ", fecha " + closingDay() + ", vence " + dueDay() + ", " + status + "]";
    }
}
