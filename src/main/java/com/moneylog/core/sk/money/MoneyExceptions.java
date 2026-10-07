package com.moneylog.core.sk.money;

import com.moneylog.core.sk.exception.BusinessRuleException;

/**
 * Exceções do value object {@link Money}.
 */
public final class MoneyExceptions {

    private MoneyExceptions() {
    }

    public static class CurrencyMismatchException extends BusinessRuleException {
        public CurrencyMismatchException(CurrencyCode left, CurrencyCode right) {
            super("CURRENCY_MISMATCH", "Operação entre moedas diferentes: " + left + " e " + right + ".");
        }
    }

    public static class InvalidMoneyAmountException extends BusinessRuleException {
        public InvalidMoneyAmountException(String amount) {
            super("MONEY_INVALID_AMOUNT",
                    "Valor monetário inválido: '" + amount + "'. Use até 2 casas decimais com ponto (ex.: 1200.00).");
        }
    }

    public static class MoneyOverflowException extends BusinessRuleException {
        public MoneyOverflowException(ArithmeticException cause) {
            super("MONEY_OVERFLOW", "Valor monetário fora do limite suportado.", cause);
        }
    }

    /** S-78: valor de entrada acima do teto (erro de digitação provável). */
    public static class AmountAboveLimitException extends BusinessRuleException {
        public AmountAboveLimitException(String field, Money max) {
            super("AMOUNT_ABOVE_LIMIT", "O valor de " + field + " passa do máximo permitido (" + max + ").");
        }
    }

    public static class InvalidMoneyAllocationException extends BusinessRuleException {
        public InvalidMoneyAllocationException(String reason) {
            super("MONEY_INVALID_ALLOCATION", "Divisão de valor inválida: " + reason);
        }
    }

    public static class UnsupportedCurrencyException extends BusinessRuleException {
        public UnsupportedCurrencyException(String code) {
            super("CURRENCY_NOT_SUPPORTED", "Moeda não suportada: '" + code + "'. Use um código ISO 4217 com 2 casas decimais.");
        }
    }
}
