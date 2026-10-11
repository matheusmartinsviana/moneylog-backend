package com.moneylog.core.auth.app.usecase;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.auth.app.AuthSession;
import com.moneylog.core.auth.app.SessionOpener;
import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.OwnerAccess;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.exception.AuthExceptions.InvalidCredentialsException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;

public class LoginUseCaseImpl implements LoginUseCase {

    private final UserGateway users;
    private final PasswordVerifier passwordVerifier;
    private final SessionOpener sessions;
    private final IdGenerator ids;
    private final TimeProvider time;

    public LoginUseCaseImpl(UserGateway users, PasswordVerifier passwordVerifier, SessionOpener sessions, IdGenerator ids,
                            TimeProvider time) {
        this.users = users;
        this.passwordVerifier = passwordVerifier;
        this.sessions = sessions;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public AuthSession handle(LoginCommand command) {
        Optional<User> user = findUser(command.email()).filter(this::isOwner);
        String password = command.password() == null ? "" : command.password();
        if (user.isEmpty()) {
            passwordVerifier.verifyDummy(password);
            throw new InvalidCredentialsException();
        }
        if (!passwordMatches(user.get(), password)) {
            throw new InvalidCredentialsException();
        }
        Instant now = time.now();
        UUID familyId = ids.next();
        return sessions.open(user.get().id(), familyId, now);
    }

    private boolean isOwner(User candidate) {
        return users.findOwner().map(owner -> owner.id().equals(candidate.id())).orElse(false);
    }

    private boolean passwordMatches(User user, String password) {
        OwnerAccess access = users.findAccess(user.id()).orElse(null);
        if (access != null && access.hasStoredPassword()) {
            return passwordVerifier.matches(password, access.passwordHash());
        }
        if (access != null && access.passwordSetupPending()) {
            passwordVerifier.verifyDummy(password);
            return false;
        }
        return passwordVerifier.verifyOwner(password);
    }

    private Optional<User> findUser(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return users.findByEmail(User.normalize(email));
    }
}
