package com.moneylog.core.quickentry.exception;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.NotFoundException;

public final class QuickEntryExceptions {

    private QuickEntryExceptions() {
    }

    public static class QuickTokenNotFoundException extends NotFoundException {
        public QuickTokenNotFoundException() {
            super(QuickEntryErrorCodes.QUICK_TOKEN_NOT_FOUND, "Token não encontrado.");
        }
    }

    public static class QuickTokenLimitReachedException extends ConflictException {
        public QuickTokenLimitReachedException(int max) {
            super(QuickEntryErrorCodes.QUICK_TOKEN_LIMIT_REACHED,
                    "Você já tem " + max + " tokens ativos. Revogue um para gerar outro.");
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
