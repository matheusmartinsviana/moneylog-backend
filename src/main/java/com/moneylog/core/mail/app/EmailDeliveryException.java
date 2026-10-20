package com.moneylog.core.mail.app;

import com.moneylog.core.sk.exception.DomainException;

public class EmailDeliveryException extends DomainException {

    public static final String CODE = "EMAIL_DELIVERY_FAILED";

    public EmailDeliveryException(String message) {
        super(CODE, message);
    }
}
