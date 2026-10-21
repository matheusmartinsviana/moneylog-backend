package com.moneylog.core.quickentry.app.usecase;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.quickentry.app.QuickEntryTokenView;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.sk.time.TimeProvider;

public class ListQuickEntryTokensUseCaseImpl implements ListQuickEntryTokensUseCase {

    private final QuickEntryTokenGateway tokens;
    private final AccountGateway accounts;
    private final TimeProvider time;

    public ListQuickEntryTokensUseCaseImpl(QuickEntryTokenGateway tokens, AccountGateway accounts, TimeProvider time) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.time = time;
    }

    @Override
    public List<QuickEntryTokenView> handle(ListQuickEntryTokensQuery query) {
        Instant now = time.now();
        Map<UUID, String> names = new HashMap<>();
        return tokens.listNewestFirst(query.userId()).stream()
                .map(token -> QuickEntryTokenView.of(token, names.computeIfAbsent(token.accountId(),
                        id -> AccountNames.of(accounts, query.userId(), id)), now))
                .toList();
    }
}
