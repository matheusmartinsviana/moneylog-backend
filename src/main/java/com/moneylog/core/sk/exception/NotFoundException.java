package com.moneylog.core.sk.exception;

/**
 * Recurso inexistente <b>ou de outro usuário</b> (não revela existência). Vira 404.
 */
public abstract class NotFoundException extends DomainException {

    protected NotFoundException(String code, String message) {
        super(code, message);
    }
}
