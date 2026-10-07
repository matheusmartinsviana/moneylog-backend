package com.moneylog.core.sk.exception;

/**
 * Violação de regra de negócio com {@code code} estável (ex.: {@code INVOICE_NOT_CLOSED}). Vira 422.
 */
public abstract class BusinessRuleException extends DomainException {

    protected BusinessRuleException(String code, String message) {
        super(code, message);
    }

    protected BusinessRuleException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
