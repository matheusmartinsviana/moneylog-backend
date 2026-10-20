package com.moneylog.core.mail.app;

import java.util.Objects;

public record EmailDelivery(String messageId) {

    public EmailDelivery {
        Objects.requireNonNull(messageId, "messageId");
    }
}
