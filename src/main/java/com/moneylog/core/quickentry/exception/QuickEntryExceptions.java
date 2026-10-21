package com.moneylog.core.quickentry.exception;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.NotFoundException;
import com.moneylog.core.sk.exception.UnauthorizedException;

public final class QuickEntryExceptions {

    private QuickEntryExceptions() {
    }

    public static class QuickTokenNotFoundException extends NotFoundException {
        public QuickTokenNotFoundException() {
            super(QuickEntryErrorCodes.QUICK_TOKEN_NOT_FOUND, "Token não encontrado.");
        }
    }

    public static class QuickUserNotActiveException extends UnauthorizedException {
        public QuickUserNotActiveException() {
            super("UNAUTHENTICATED", "Autenticação necessária.");
        }
    }

    public static class QuickTokenLimitReachedException extends ConflictException {
        public QuickTokenLimitReachedException(int max) {
            super(QuickEntryErrorCodes.QUICK_TOKEN_LIMIT_REACHED,
                    "Você já tem " + max + " tokens ativos. Revogue um para gerar outro.");
        }
    }

    public static class QuickAmountInvalidException extends BusinessRuleException {
        public QuickAmountInvalidException() {
            super(QuickEntryErrorCodes.QUICK_AMOUNT_INVALID, "Não entendi o valor. Digite algo como 35,50.");
        }
    }

    public static class QuickAmountAmbiguousException extends BusinessRuleException {
        public QuickAmountAmbiguousException() {
            super(QuickEntryErrorCodes.QUICK_AMOUNT_AMBIGUOUS, "Use vírgula nos centavos, por exemplo 1.234,00.");
        }
    }

    public static class QuickCategoryNotFoundException extends BusinessRuleException {
        public QuickCategoryNotFoundException() {
            super(QuickEntryErrorCodes.QUICK_CATEGORY_NOT_FOUND, "Não achei a categoria enviada. Escolha outra ou deixe em branco.");
        }
    }

    public static class QuickDateOutOfRangeException extends BusinessRuleException {
        public QuickDateOutOfRangeException() {
            super(QuickEntryErrorCodes.QUICK_DATE_OUT_OF_RANGE, "A data não pode ser futura nem anterior a 366 dias.");
        }
    }

    public static class QuickLimitReachedException extends BusinessRuleException {
        public QuickLimitReachedException(String detail) {
            super(QuickEntryErrorCodes.QUICK_LIMIT_REACHED, detail);
        }
    }

    public static class QuickAccountNotAllowedException extends BusinessRuleException {
        public QuickAccountNotAllowedException() {
            super(QuickEntryErrorCodes.QUICK_ACCOUNT_NOT_ALLOWED,
                    "Esta conta não aceita lançamento rápido. Escolha uma conta corrente, poupança ou carteira ativa.");
        }
    }

    public static class QuickTokenValidityInvalidException extends BusinessRuleException {
        public QuickTokenValidityInvalidException(int maxDays) {
            super(QuickEntryErrorCodes.QUICK_TOKEN_VALIDITY_INVALID, "A validade do token vai de 1 a " + maxDays + " dias.");
        }
    }
}
