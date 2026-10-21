package com.moneylog.core.quickentry.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickAccountNotAllowedException;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickTokenLimitReachedException;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickTokenValidityInvalidException;

public final class QuickEntryTokenPolicy {

    public static final int HARD_MAX_VALIDITY_DAYS = 90;
    public static final Set<AccountType> ALLOWED_ACCOUNT_TYPES = Set.copyOf(EnumSet.of(AccountType.CHECKING, AccountType.SAVINGS, AccountType.WALLET));

    private final int defaultValidityDays;
    private final int maxValidityDays;
    private final int maxActiveTokens;

    public QuickEntryTokenPolicy(int defaultValidityDays, int maxValidityDays, int maxActiveTokens) {
        if (maxValidityDays < 1 || maxValidityDays > HARD_MAX_VALIDITY_DAYS) {
            throw new IllegalArgumentException("a validade máxima deve ficar entre 1 e " + HARD_MAX_VALIDITY_DAYS + " dias");
        }
        if (defaultValidityDays < 1 || defaultValidityDays > maxValidityDays) {
            throw new IllegalArgumentException("a validade padrão deve ficar entre 1 e a validade máxima");
        }
        if (maxActiveTokens < 1) {
            throw new IllegalArgumentException("o máximo de tokens ativos deve ser positivo");
        }
        this.defaultValidityDays = defaultValidityDays;
        this.maxValidityDays = maxValidityDays;
        this.maxActiveTokens = maxActiveTokens;
    }

    public Instant expiresAt(Instant now, Integer requestedDays) {
        int days = requestedDays == null ? defaultValidityDays : requestedDays;
        if (days < 1 || days > maxValidityDays) {
            throw new QuickTokenValidityInvalidException(maxValidityDays);
        }
        return now.plus(Duration.ofDays(days));
    }

    public void ensureCanIssue(long activeTokens) {
        if (activeTokens >= maxActiveTokens) {
            throw new QuickTokenLimitReachedException(maxActiveTokens);
        }
    }

    public void ensureAccountAllowed(AccountType type, AccountStatus status) {
        if (status != AccountStatus.ACTIVE || !ALLOWED_ACCOUNT_TYPES.contains(type)) {
            throw new QuickAccountNotAllowedException();
        }
    }

    public int maxActiveTokens() {
        return maxActiveTokens;
    }
}
