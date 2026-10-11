package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.OwnerAccess;
import com.moneylog.core.auth.domain.SetupCode;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.sk.time.TimeProvider;

public class PrepareFirstAccessUseCaseImpl implements PrepareFirstAccessUseCase {

    private final UserGateway users;
    private final PasswordVerifier passwordVerifier;
    private final TimeProvider time;

    public PrepareFirstAccessUseCaseImpl(UserGateway users, PasswordVerifier passwordVerifier, TimeProvider time) {
        this.users = users;
        this.passwordVerifier = passwordVerifier;
        this.time = time;
    }

    @Override
    public FirstAccessPrepared handle(PrepareFirstAccessCommand command) {
        User owner = users.findOwner().orElse(null);
        if (owner == null) {
            return new FirstAccessPrepared(false, null);
        }
        OwnerAccess access = users.findAccess(owner.id()).orElse(new OwnerAccess(null, false, null, null));
        if (!access.passwordSetupRequired(passwordVerifier.hasEnvironmentHash())) {
            return new FirstAccessPrepared(false, null);
        }
        boolean provided = command.providedCode() != null && !command.providedCode().isBlank();
        String code = provided ? command.providedCode().strip() : SetupCode.generate();
        if (provided && SetupCode.weakness(code) != null) {
            throw new IllegalStateException("MONEYLOG_OWNER_SETUP_CODE é fraco (" + SetupCode.weakness(code) + "): use pelo menos "
                    + SetupCode.MIN_PROVIDED_LENGTH + " caracteres variados, por exemplo openssl rand -hex 16.");
        }
        var now = time.now();
        users.storeSetupCode(owner.id(), SetupCode.hash(code), now.plus(command.ttl()), now);
        return new FirstAccessPrepared(true, provided ? null : code);
    }
}
