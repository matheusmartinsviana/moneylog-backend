package com.moneylog.core.transaction.exception;

import com.moneylog.core.sk.exception.BusinessRuleException;

public final class CardCashOutExceptions {

    private CardCashOutExceptions() {
    }

    public static class CardCashOutInvalidException extends BusinessRuleException {
        public CardCashOutInvalidException(String reason) {
            super("CARD_CASH_OUT_INVALID", "Pix no crédito inválido: " + reason);
        }
    }
}
