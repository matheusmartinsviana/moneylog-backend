package com.moneylog.core.auth.exception;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.UnauthorizedException;

public final class AuthExceptions {

    private AuthExceptions() {
    }

    public static class InvalidCredentialsException extends UnauthorizedException {
        public InvalidCredentialsException() {
            super("INVALID_CREDENTIALS", "E-mail ou senha inválidos.");
        }
    }

    public static class RefreshInvalidException extends UnauthorizedException {
        public RefreshInvalidException() {
            super("REFRESH_INVALID", "Sessão inválida ou expirada. Entre de novo.");
        }
    }

    public static class InvalidSetupCodeException extends UnauthorizedException {
        public InvalidSetupCodeException() {
            super("INVALID_SETUP_CODE", "Código de primeiro acesso inválido ou expirado.");
        }
    }

    public static class WeakPasswordException extends BusinessRuleException {
        public WeakPasswordException() {
            super("WEAK_PASSWORD", "A senha precisa ter pelo menos 12 caracteres (no máximo 72 bytes) e não pode ser igual ao e-mail.");
        }
    }
}
