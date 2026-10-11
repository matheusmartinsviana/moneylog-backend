package com.moneylog.core.sk.exception;

public abstract class UnauthorizedException extends DomainException {

    protected UnauthorizedException(String code, String message) {
        super(code, message);
    }
}
