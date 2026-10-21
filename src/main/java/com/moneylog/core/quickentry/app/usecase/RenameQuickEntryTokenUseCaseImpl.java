package com.moneylog.core.quickentry.app.usecase;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.quickentry.app.QuickEntryTokenView;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickTokenNotFoundException;
import com.moneylog.core.sk.time.TimeProvider;

public class RenameQuickEntryTokenUseCaseImpl implements RenameQuickEntryTokenUseCase {

    private final QuickEntryTokenGateway tokens;
    private final AccountGateway accounts;
    private final TimeProvider time;

    public RenameQuickEntryTokenUseCaseImpl(QuickEntryTokenGateway tokens, AccountGateway accounts, TimeProvider time) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.time = time;
    }

    @Override
    public QuickEntryTokenView handle(RenameQuickEntryTokenCommand command) {
        QuickEntryToken current = tokens.findById(command.userId(), command.tokenId()).orElseThrow(QuickTokenNotFoundException::new);
        QuickEntryToken renamed = current.rename(command.label());
        tokens.update(renamed);
        return QuickEntryTokenView.of(renamed, AccountNames.of(accounts, command.userId(), renamed.accountId()), time.now());
    }
}
