package com.moneylog.core.quickentry.app.usecase;

import java.time.Instant;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.quickentry.app.QuickEntryTokenView;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryUserLock;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.TokenRevocationReason;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickTokenNotFoundException;
import com.moneylog.core.sk.time.TimeProvider;

public class RevokeQuickEntryTokenUseCaseImpl implements RevokeQuickEntryTokenUseCase {

    private final QuickEntryTokenGateway tokens;
    private final AccountGateway accounts;
    private final QuickEntryUserLock userLock;
    private final TimeProvider time;

    public RevokeQuickEntryTokenUseCaseImpl(QuickEntryTokenGateway tokens, AccountGateway accounts, QuickEntryUserLock userLock,
            TimeProvider time) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.userLock = userLock;
        this.time = time;
    }

    @Override
    public Revoked handle(RevokeQuickEntryTokenCommand command) {
        userLock.lock(command.userId());
        QuickEntryToken current = tokens.findById(command.userId(), command.tokenId()).orElseThrow(QuickTokenNotFoundException::new);
        Instant now = time.now();
        QuickEntryToken revoked = current.revoke(TokenRevocationReason.USER, now);
        boolean changed = revoked != current;
        if (changed) {
            tokens.update(revoked);
        }
        String accountName = AccountNames.of(accounts, command.userId(), revoked.accountId());
        return new Revoked(QuickEntryTokenView.of(revoked, accountName, now), changed);
    }
}
