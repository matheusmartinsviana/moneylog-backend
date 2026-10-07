package com.moneylog.core.auth.exception;

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
}
