package com.moneylog.core.auth.app.usecase;

import java.time.Instant;
import java.util.Optional;

import com.moneylog.core.auth.app.AuthSession;
import com.moneylog.core.auth.app.SessionOpener;
import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.OwnerAccess;
import com.moneylog.core.auth.domain.PasswordPolicy;
import com.moneylog.core.auth.domain.SetupCode;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.exception.AuthExceptions.InvalidSetupCodeException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;

public class SetupPasswordUseCaseImpl implements SetupPasswordUseCase {

    private final UserGateway users;
    private final PasswordVerifier passwordVerifier;
    private final RefreshTokenGateway refreshTokens;
    private final SessionOpener sessions;
    private final IdGenerator ids;
    private final TimeProvider time;

    public SetupPasswordUseCaseImpl(UserGateway users, PasswordVerifier passwordVerifier, RefreshTokenGateway refreshTokens,
                                    SessionOpener sessions, IdGenerator ids, TimeProvider time) {
        this.users = users;
        this.passwordVerifier = passwordVerifier;
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public AuthSession handle(SetupPasswordCommand command) {
        Instant now = time.now();
        User owner = users.findOwner().orElseThrow(InvalidSetupCodeException::new);
        OwnerAccess access = users.findAccess(owner.id()).orElseThrow(InvalidSetupCodeException::new);

        boolean emailOk = owner.email().equals(normalizeOrNull(command.email()));
        boolean codeOk = command.setupCode() != null
                && SetupCode.sameHash(SetupCode.hash(command.setupCode()), access.setupCodeHash());
        boolean required = access.passwordSetupRequired(passwordVerifier.hasEnvironmentHash());
        boolean fresh = access.setupCodeValidAt(now);
        if (!(emailOk & codeOk & required & fresh)) {
            throw new InvalidSetupCodeException();
        }

        PasswordPolicy.check(command.newPassword(), owner.email());
        String hash = passwordVerifier.encode(command.newPassword());
        if (!users.completeSetup(owner.id(), access.setupCodeHash(), hash, now)) {
            throw new InvalidSetupCodeException();
        }
        refreshTokens.revokeAllByUser(owner.id(), now);
        return sessions.open(owner.id(), ids.next(), now);
    }

    private static String normalizeOrNull(String email) {
        try {
            return User.normalize(email);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
