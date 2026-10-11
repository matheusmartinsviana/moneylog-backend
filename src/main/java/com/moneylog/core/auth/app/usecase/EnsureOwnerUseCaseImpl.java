package com.moneylog.core.auth.app.usecase;

import java.util.Optional;

import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.OwnerAccess;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;

public class EnsureOwnerUseCaseImpl implements EnsureOwnerUseCase {

    private final UserGateway users;
    private final IdGenerator ids;
    private final RefreshTokenGateway refreshTokens;
    private final TimeProvider time;

    public EnsureOwnerUseCaseImpl(UserGateway users, IdGenerator ids, RefreshTokenGateway refreshTokens, TimeProvider time) {
        this.users = users;
        this.ids = ids;
        this.refreshTokens = refreshTokens;
        this.time = time;
    }

    @Override
    public OwnerEnsured handle(EnsureOwnerCommand command) {
        String email = User.normalize(command.email());
        Optional<User> existing = users.findOwner();
        if (existing.isEmpty()) {
            User owner = new User(ids.next(), email);
            users.insert(owner);
            return new OwnerEnsured(owner, true, null, false);
        }
        User owner = existing.get();
        String previousEmail = null;
        if (!owner.email().equals(email)) {
            users.updateEmail(owner.id(), email);
            previousEmail = owner.email();
            owner = new User(owner.id(), email);
        }
        boolean reset = command.resetPassword() && resetPassword(owner);
        return new OwnerEnsured(owner, false, previousEmail, reset);
    }

    private boolean resetPassword(User owner) {
        OwnerAccess access = users.findAccess(owner.id()).orElse(null);
        if (access != null && !access.hasStoredPassword() && access.passwordSetupPending()) {
            return false;
        }
        var now = time.now();
        users.resetPassword(owner.id(), now);
        refreshTokens.revokeAllByUser(owner.id(), now);
        return true;
    }
}
