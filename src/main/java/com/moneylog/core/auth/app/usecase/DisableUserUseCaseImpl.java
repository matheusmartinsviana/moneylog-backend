package com.moneylog.core.auth.app.usecase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.ManagedUser;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.exception.UserManagementExceptions.UserNotFoundException;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryUserLock;
import com.moneylog.core.quickentry.domain.TokenRevocationReason;
import com.moneylog.core.sk.time.TimeProvider;

public class DisableUserUseCaseImpl implements DisableUserUseCase {

    private static final Logger log = LoggerFactory.getLogger("com.moneylog.security.events");

    private final UserGateway users;
    private final RefreshTokenGateway refreshTokens;
    private final QuickEntryTokenGateway quickEntryTokens;
    private final QuickEntryUserLock quickEntryLock;
    private final TimeProvider time;

    public DisableUserUseCaseImpl(UserGateway users, RefreshTokenGateway refreshTokens, QuickEntryTokenGateway quickEntryTokens,
                                  QuickEntryUserLock quickEntryLock, TimeProvider time) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.quickEntryTokens = quickEntryTokens;
        this.quickEntryLock = quickEntryLock;
        this.time = time;
    }

    @Override
    public ManagedUser handle(DisableUserCommand command) {
        User user = users.findById(command.userId()).orElseThrow(() -> new UserNotFoundException(command.userId()));
        User disabled = user.disabled();
        var now = time.now();
        quickEntryLock.lock(disabled.id());
        users.updateStatus(disabled.id(), disabled.status(), now);
        refreshTokens.revokeAllByUser(disabled.id(), now);
        int revoked = quickEntryTokens.revokeAllActive(disabled.id(), TokenRevocationReason.USER_DISABLED, now);
        log.info("security_event event=quick_tokens_revoked_on_disable user={} count={}", disabled.id(), revoked);
        return users.findManagedById(disabled.id()).orElseThrow(() -> new UserNotFoundException(command.userId()));
    }
}
