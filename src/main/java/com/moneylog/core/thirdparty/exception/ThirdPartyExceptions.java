package com.moneylog.core.thirdparty.exception;

import com.moneylog.core.sk.exception.BusinessRuleException;

public final class ThirdPartyExceptions {

    private ThirdPartyExceptions() {
    }

    public static class ThirdPartyExpenseInvalidException extends BusinessRuleException {
        public ThirdPartyExpenseInvalidException(String reason) {
            super("THIRD_PARTY_EXPENSE_INVALID", "Despesa de terceiro inválida: " + reason);
        }
    }

    public static class ThirdPartyExpenseAlreadyMarkedException extends BusinessRuleException {
        public ThirdPartyExpenseAlreadyMarkedException() {
            super("THIRD_PARTY_EXPENSE_ALREADY_MARKED", "Esta despesa já está marcada como de terceiro.");
        }
    }
}
