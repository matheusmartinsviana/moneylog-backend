package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.domain.User;

public interface EnsureOwnerUseCase {

    OwnerEnsured handle(EnsureOwnerCommand command);

    record EnsureOwnerCommand(String email, boolean resetPassword) {

        public EnsureOwnerCommand(String email) {
            this(email, false);
        }
    }

    record OwnerEnsured(User owner, boolean created, String previousEmail, boolean passwordReset) {

        public OwnerEnsured(User owner, boolean created, String previousEmail) {
            this(owner, created, previousEmail, false);
        }
    }
}
