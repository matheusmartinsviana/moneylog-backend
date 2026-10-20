package com.moneylog.core.auth.app.usecase;

import java.util.Optional;

import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.OwnerAccess;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.domain.UserRole;
import com.moneylog.core.auth.exception.OwnerEmailConflictException;
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
        Optional<User> existing = users.findByRole(UserRole.OWNER);
        if (existing.isEmpty()) {
            return withoutOwner(email);
        }
        User owner = existing.get();
        if (!owner.email().equals(email)) {
            throw new OwnerEmailConflictException();
        }
        boolean reset = command.resetPassword() && resetPassword(owner);
        return new OwnerEnsured(owner, false, reset);
    }

    private OwnerEnsured withoutOwner(String email) {
        Optional<User> sameEmail = users.findByEmail(email);
        if (sameEmail.isPresent()) {
            User promoted = sameEmail.get().promotedToOwner();
            users.updateRole(promoted.id(), promoted.role(), time.now());
            return new OwnerEnsured(promoted, false, false, true);
        }
        User owner = User.owner(ids.next(), email);
        users.insert(owner);
        return new OwnerEnsured(owner, true, false);
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
