package com.moneylog.core.sk.exception;

public abstract class BusinessRuleException extends DomainException {

    protected BusinessRuleException(String code, String message) {
        super(code, message);
    }

    protected BusinessRuleException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
