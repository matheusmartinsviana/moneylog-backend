package com.moneylog.core.quickentry.app.usecase;

import java.time.Instant;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.quickentry.app.IssuedQuickEntryToken;
import com.moneylog.core.quickentry.app.QuickEntryTokenView;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryUserLock;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.QuickEntryTokenPolicy;
import com.moneylog.core.quickentry.domain.QuickEntryTokenSecret;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;

public class CreateQuickEntryTokenUseCaseImpl implements CreateQuickEntryTokenUseCase {

    private final QuickEntryTokenGateway tokens;
    private final AccountGateway accounts;
    private final QuickEntryUserLock userLock;
    private final QuickEntryTokenPolicy policy;
    private final IdGenerator ids;
    private final TimeProvider time;

    public CreateQuickEntryTokenUseCaseImpl(QuickEntryTokenGateway tokens, AccountGateway accounts, QuickEntryUserLock userLock,
                                            QuickEntryTokenPolicy policy, IdGenerator ids, TimeProvider time) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.userLock = userLock;
        this.policy = policy;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public IssuedQuickEntryToken handle(CreateQuickEntryTokenCommand command) {
        userLock.lock(command.userId());
        Account account = accounts.findById(command.userId(), command.accountId()).orElseThrow(() -> new AccountNotFoundException(command.accountId())).value();
        policy.ensureAccountAllowed(account.type(), account.status());
        Instant now = time.now();
        Instant expiresAt = policy.expiresAt(now, command.expiresInDays());
        policy.ensureCanIssue(tokens.countActive(command.userId(), now));
        QuickEntryTokenSecret secret = QuickEntryTokenSecret.generate();
        QuickEntryToken token = QuickEntryToken.issue(ids.next(), command.userId(), account.id(), command.label(), secret.prefix(),
                secret.hash(), now, expiresAt);
        tokens.insert(token);
        return new IssuedQuickEntryToken(QuickEntryTokenView.of(token, account.name(), now), secret.value());
    }
}
