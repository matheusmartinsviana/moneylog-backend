package com.moneylog.core.account.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import com.moneylog.core.account.exception.AccountExceptions.AccountAlreadyArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountCurrencyNotSupportedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountFieldRequiredException;
import com.moneylog.core.account.exception.AccountExceptions.AccountInstitutionTooLongException;
import com.moneylog.core.sk.text.PlainText;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.account.exception.AccountExceptions.AccountNameRequiredException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNameTooLongException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.account.exception.AccountExceptions.AccountOpeningLockedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountOwnerRequiredException;
import com.moneylog.core.account.exception.AccountExceptions.AccountWithBalanceCannotBeArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountWithFutureBalanceCannotBeArchivedException;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.Postings;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public final class Account {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int INSTITUTION_MAX_LENGTH = 100;

    private final UUID id;
    private final UUID userId;
    private String name;
    private AccountType type;
    private String institution;
    private Money openingBalance;
    private LocalDate openingDate;
    private AccountStatus status;
    private final long version;

    private Account(UUID id, UUID userId, String name, AccountType type, String institution, Money openingBalance,
                    LocalDate openingDate, AccountStatus status, long version) {
        this.id = required(id, "id");
        this.userId = requireOwner(userId);
        this.name = requireName(name);
        this.type = required(type, "type");
        this.institution = requireInstitution(institution);
        this.openingBalance = AmountLimit.ensureWithinLimit(requireBrl(required(openingBalance, "openingBalance")), "openingBalance");
        this.openingDate = required(openingDate, "openingDate");
        this.status = required(status, "status");
        this.version = version;
    }

    public static Account open(UUID id, UUID userId, String name, AccountType type, String institution,
                               Money openingBalance, LocalDate openingDate) {
        return new Account(id, userId, name, type, institution, openingBalance, FinancialDates.requireInWindow(openingDate, "openingDate"),
                AccountStatus.ACTIVE, 0L);
    }

    public static Account restore(UUID id, UUID userId, String name, AccountType type, String institution,
                                  Money openingBalance, LocalDate openingDate, AccountStatus status, long version) {
        return new Account(id, userId, name, type, institution, openingBalance, openingDate, status, version);
    }

    public List<Posting> openingPostings() {
        if (openingBalance.isZero()) {
            return List.of();
        }
        return Postings.balanced(
                new Posting(BookRef.account(id), openingBalance, openingDate),
                new Posting(BookRef.equity(), openingBalance.negate(), openingDate));
    }

    public BookRef book() {
        return BookRef.account(id);
    }

    public void rename(String newName) {
        this.name = requireName(newName);
    }

    public void updateDetails(String newName, String newInstitution) {
        String validName = requireName(newName);
        String validInstitution = requireInstitution(newInstitution);
        this.name = validName;
        this.institution = validInstitution;
    }

    public boolean openingMatches(AccountType otherType, Money otherBalance, LocalDate otherDate) {
        return type == otherType && openingBalance.equals(otherBalance) && openingDate.equals(otherDate);
    }

    public List<Posting> correctOpening(AccountType newType, Money newBalance, LocalDate newDate, boolean hasActiveMovements) {
        AccountType validType = required(newType, "type");
        Money validBalance = AmountLimit.ensureWithinLimit(requireBrl(required(newBalance, "openingBalance")), "openingBalance");
        LocalDate validDate = FinancialDates.requireInWindow(required(newDate, "openingDate"), "openingDate");
        if (hasActiveMovements) {
            throw new AccountOpeningLockedException();
        }
        ensureCanReceiveTransactions();
        this.type = validType;
        this.openingBalance = validBalance;
        this.openingDate = validDate;
        return openingPostings();
    }

    public void archive(Collection<Posting> accountPostings, LocalDate today) {
        if (status == AccountStatus.ARCHIVED) {
            throw new AccountAlreadyArchivedException();
        }
        ensureArchivable(accountPostings, today);
        this.status = AccountStatus.ARCHIVED;
    }

    public boolean canBeArchived(Collection<Posting> accountPostings, LocalDate today) {
        if (status == AccountStatus.ARCHIVED) {
            return false;
        }
        try {
            ensureArchivable(accountPostings, today);
            return true;
        } catch (AccountWithBalanceCannotBeArchivedException | AccountWithFutureBalanceCannotBeArchivedException e) {
            return false;
        }
    }

    private void ensureArchivable(Collection<Posting> accountPostings, LocalDate today) {
        Objects.requireNonNull(accountPostings, "accountPostings");
        Objects.requireNonNull(today, "today");
        Money balance = Money.zero(currency());
        Map<LocalDate, Money> future = new TreeMap<>();
        for (Posting posting : accountPostings) {
            if (!book().equals(posting.book())) {
                continue;
            }
            if (posting.effectiveDate().isAfter(today)) {
                future.merge(posting.effectiveDate(), posting.amount(), Money::add);
            } else {
                balance = balance.add(posting.amount());
            }
        }
        if (!balance.isZero()) {
            throw new AccountWithBalanceCannotBeArchivedException(balance);
        }
        for (Map.Entry<LocalDate, Money> day : future.entrySet()) {
            balance = balance.add(day.getValue());
            if (!balance.isZero()) {
                throw new AccountWithFutureBalanceCannotBeArchivedException(day.getKey(), balance);
            }
        }
    }

    public void unarchive() {
        if (status != AccountStatus.ARCHIVED) {
            throw new AccountNotArchivedException();
        }
        this.status = AccountStatus.ACTIVE;
    }

    public void ensureCanReceiveTransactions() {
        if (status == AccountStatus.ARCHIVED) {
            throw new AccountArchivedException();
        }
    }

    public void ensureBelongsTo(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new AccountNotFoundException(id);
        }
    }

    public LiquidityClass liquidity() {
        return type.liquidity();
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
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

    public AccountType type() {
        return type;
    }

    public String institution() {
        return institution;
    }

    public Money openingBalance() {
        return openingBalance;
    }

    public LocalDate openingDate() {
        return openingDate;
    }

    public CurrencyCode currency() {
        return openingBalance.currency();
    }

    public AccountStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    private static UUID requireOwner(UUID userId) {
        if (userId == null) {
            throw new AccountOwnerRequiredException();
        }
        return userId;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new AccountNameRequiredException();
        }
        PlainText.requireClean(name, "name", false);
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new AccountNameTooLongException(NAME_MAX_LENGTH);
        }
        return trimmed;
    }

    private static Money requireBrl(Money money) {
        if (!CurrencyCode.BRL.equals(money.currency())) {
            throw new AccountCurrencyNotSupportedException(money.currency());
        }
        return money;
    }

    private static String requireInstitution(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        PlainText.requireClean(value, "institution", false);
        String trimmed = value.strip();
        if (trimmed.length() > INSTITUTION_MAX_LENGTH) {
            throw new AccountInstitutionTooLongException(INSTITUTION_MAX_LENGTH);
        }
        return trimmed;
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new AccountFieldRequiredException(field);
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Account other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Account[" + id + ", " + name + ", " + type + ", " + status + "]";
    }
}
