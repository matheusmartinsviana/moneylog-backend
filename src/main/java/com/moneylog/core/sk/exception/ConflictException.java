package com.moneylog.core.sk.exception;

/**
 * Conflito de estado (versão desatualizada, duplicidade, idempotência). Vira 409.
 */
public abstract class ConflictException extends DomainException {

    protected ConflictException(String code, String message) {
        super(code, message);
    }
}
