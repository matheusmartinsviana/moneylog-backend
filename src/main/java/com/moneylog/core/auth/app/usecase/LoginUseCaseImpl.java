package com.moneylog.core.auth.app.usecase;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.auth.app.AuthSession;
import com.moneylog.core.auth.app.SessionOpener;
import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.exception.AuthExceptions.InvalidCredentialsException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;

/**
 * Login do dono (ADR-003). E-mail desconhecido e senha errada têm a mesma resposta e o mesmo custo: no primeiro caso a
 * senha é comparada com um hash fictício (S-86). O hash real é o do dono, único usuário.
 */
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
        Optional<User> user = findUser(command.email());
        String password = command.password() == null ? "" : command.password();
        if (user.isEmpty()) {
            passwordVerifier.verifyDummy(password);
            throw new InvalidCredentialsException();
        }
        if (!passwordVerifier.verifyOwner(password)) {
            throw new InvalidCredentialsException();
        }
        Instant now = time.now();
        UUID familyId = ids.next();
        return sessions.open(user.get().id(), familyId, now);
    }

    private Optional<User> findUser(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return users.findByEmail(User.normalize(email));
    }
}
