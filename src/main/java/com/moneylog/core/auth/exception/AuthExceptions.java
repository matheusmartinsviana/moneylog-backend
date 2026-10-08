package com.moneylog.core.auth.exception;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.UnauthorizedException;

/**
 * Exceções de autenticação. A mensagem é a mesma para qualquer causa (não revela se o e-mail existe).
 */
public final class AuthExceptions {

    private AuthExceptions() {
    }

    /** E-mail ou senha errados (mesma resposta para os dois). */
    public static class InvalidCredentialsException extends UnauthorizedException {
        public InvalidCredentialsException() {
            super("INVALID_CREDENTIALS", "E-mail ou senha inválidos.");
        }
    }

    /** Refresh token ausente, desconhecido, expirado, revogado ou reutilizado fora da janela de tolerância. */
    public static class RefreshInvalidException extends UnauthorizedException {
        public RefreshInvalidException() {
            super("REFRESH_INVALID", "Sessão inválida ou expirada. Entre de novo.");
        }
    }

    /** Primeiro acesso: e-mail, código ou estado inválidos (mesma resposta para qualquer causa; não revela qual). */
    public static class InvalidSetupCodeException extends UnauthorizedException {
        public InvalidSetupCodeException() {
            super("INVALID_SETUP_CODE", "Código de primeiro acesso inválido ou expirado.");
        }
    }

    /** A nova senha não cumpre a regra mínima. */
    public static class WeakPasswordException extends BusinessRuleException {
        public WeakPasswordException() {
            super("WEAK_PASSWORD", "A senha precisa ter pelo menos 12 caracteres (no máximo 72 bytes) e não pode ser igual ao e-mail.");
        }
    }
}
