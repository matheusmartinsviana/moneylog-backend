package com.moneylog.core.auth.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.NotFoundException;

public final class UserManagementExceptions {

    private UserManagementExceptions() {
    }

    public static class UserNotFoundException extends NotFoundException {
        public UserNotFoundException(UUID userId) {
            super(InvitationErrorCodes.USER_NOT_FOUND, "Usuário não encontrado: " + userId + ".");
        }
    }

    public static class CannotDisableOwnerException extends ConflictException {
        public CannotDisableOwnerException() {
            super(InvitationErrorCodes.CANNOT_DISABLE_OWNER, "O dono não pode ser desativado.");
        }
    }
}
