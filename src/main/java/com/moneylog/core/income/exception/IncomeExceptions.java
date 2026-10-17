package com.moneylog.core.income.exception;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.income.domain.TaxKind;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.NotFoundException;

public final class IncomeExceptions {

    private IncomeExceptions() {
    }

    public static class IncomeProfileNotFoundException extends NotFoundException {
        public IncomeProfileNotFoundException(UUID id) {
            super("INCOME_PROFILE_NOT_FOUND", "Perfil de renda não encontrado: " + id + ".");
        }
    }

    public static class IncomeProfileInvalidException extends BusinessRuleException {
        public IncomeProfileInvalidException(String reason) {
            super("INCOME_PROFILE_INVALID", "Perfil de renda inválido: " + reason);
        }
    }

    public static class IncomeProfileArchivedException extends BusinessRuleException {
        public IncomeProfileArchivedException() {
            super("INCOME_PROFILE_ARCHIVED", "O perfil de renda está arquivado.");
        }
    }

    public static class TaxTableUnavailableException extends BusinessRuleException {
        public TaxTableUnavailableException(TaxKind kind, LocalDate reference) {
            super("TAX_TABLE_UNAVAILABLE", "Não há tabela de " + kind + " vigente em " + reference + ".");
        }
    }
}
