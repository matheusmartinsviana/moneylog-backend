package com.moneylog.core.sk.exception;

/**
 * Falha de autenticação do chamador (credencial ou refresh inválidos). Vira 401.
 */
public abstract class UnauthorizedException extends DomainException {

    protected UnauthorizedException(String code, String message) {
        super(code, message);
    }
}
