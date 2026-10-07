package com.moneylog.core.auth.app.usecase;

import java.util.Optional;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.sk.id.IdGenerator;

public class EnsureOwnerUseCaseImpl implements EnsureOwnerUseCase {

    private final UserGateway users;
    private final IdGenerator ids;

    public EnsureOwnerUseCaseImpl(UserGateway users, IdGenerator ids) {
        this.users = users;
        this.ids = ids;
    }

    @Override
    public OwnerEnsured handle(EnsureOwnerCommand command) {
        String email = User.normalize(command.email());
        Optional<User> existing = users.findOwner();
        if (existing.isEmpty()) {
            User owner = new User(ids.next(), email);
            users.insert(owner);
            return new OwnerEnsured(owner, true, null);
        }
        User owner = existing.get();
        if (owner.email().equals(email)) {
            return new OwnerEnsured(owner, false, null);
        }
        users.updateEmail(owner.id(), email);
        return new OwnerEnsured(new User(owner.id(), email), false, owner.email());
    }
}
