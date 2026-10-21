package com.moneylog.core.quickentry.app.usecase;

import java.time.Instant;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.quickentry.app.QuickEntryTokenView;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.TokenRevocationReason;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickTokenNotFoundException;
import com.moneylog.core.sk.time.TimeProvider;

public class RevokeQuickEntryTokenUseCaseImpl implements RevokeQuickEntryTokenUseCase {

    private final QuickEntryTokenGateway tokens;
    private final AccountGateway accounts;
    private final TimeProvider time;

    public RevokeQuickEntryTokenUseCaseImpl(QuickEntryTokenGateway tokens, AccountGateway accounts, TimeProvider time) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.time = time;
    }

    @Override
    public Revoked handle(RevokeQuickEntryTokenCommand command) {
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
