package com.moneylog.core.transaction.exception;

import com.moneylog.core.sk.exception.BusinessRuleException;

public final class FxExceptions {

    private FxExceptions() {
    }

    public static class FxRateRequiredException extends BusinessRuleException {
        public FxRateRequiredException() {
            super("FX_RATE_REQUIRED", "Informe o câmbio (fxRate) da cobrança em moeda estrangeira.");
        }
    }

    public static class FxInvalidException extends BusinessRuleException {
        public FxInvalidException(String reason) {
            super("FX_INVALID", "Moeda estrangeira inválida: " + reason);
        }
    }
}
