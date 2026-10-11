package com.moneylog.core.importing.domain;

public enum ImportLineFlag {
    LOOKS_LIKE_INVOICE_PAYMENT(false),
    LOOKS_LIKE_INSTALLMENT(true),
    CARD_CREDIT_NOT_IMPORTED(false),
    POSSIBLE_TRANSFER(true);

    private final boolean requiresDecision;

    ImportLineFlag(boolean requiresDecision) {
        this.requiresDecision = requiresDecision;
    }

    public boolean requiresDecision() {
        return requiresDecision;
    }
}
