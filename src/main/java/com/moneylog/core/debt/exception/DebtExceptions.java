package com.moneylog.core.debt.exception;

import java.util.UUID;

import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.exception.NotFoundException;

public final class DebtExceptions {

    private DebtExceptions() {
    }

    public static class PersonNotFoundException extends NotFoundException {
        public PersonNotFoundException(UUID id) {
            super("PERSON_NOT_FOUND", "Pessoa não encontrada: " + id + ".");
        }
    }

    public static class DebtNotFoundException extends NotFoundException {
        public DebtNotFoundException(UUID id) {
            super("DEBT_NOT_FOUND", "Dívida não encontrada: " + id + ".");
        }
    }

    public static class PersonNameRequiredException extends BusinessRuleException {
        public PersonNameRequiredException() {
            super("PERSON_NAME_REQUIRED", "O nome da pessoa é obrigatório.");
        }
    }

    public static class PersonArchivedException extends BusinessRuleException {
        public PersonArchivedException() {
            super("PERSON_ARCHIVED", "A pessoa está arquivada e não recebe dívidas novas.");
        }
    }

    public static class PersonArchiveStateException extends BusinessRuleException {
        public PersonArchiveStateException(boolean archived) {
            super(archived ? "PERSON_ALREADY_ARCHIVED" : "PERSON_NOT_ARCHIVED",
                    archived ? "A pessoa já está arquivada." : "A pessoa não está arquivada.");
        }
    }

    public static class DebtFieldRequiredException extends BusinessRuleException {
        public DebtFieldRequiredException(String field) {
            super("DEBT_FIELD_REQUIRED", "Campo obrigatório da dívida ausente: " + field + ".");
        }
    }

    public static class DebtDescriptionTooLongException extends BusinessRuleException {
        public DebtDescriptionTooLongException(int max) {
            super("DEBT_DESCRIPTION_TOO_LONG", "A descrição da dívida pode ter no máximo " + max + " caracteres.");
        }
    }

    public static class InvalidDebtPrincipalException extends BusinessRuleException {
        public InvalidDebtPrincipalException() {
            super("DEBT_INVALID_PRINCIPAL", "O valor da dívida precisa ser maior que zero, em BRL.");
        }
    }

    public static class DebtDirectionMismatchException extends BusinessRuleException {
        public DebtDirectionMismatchException(DebtDirection expected, DebtDirection actual) {
            super("DEBT_DIRECTION_MISMATCH", "A movimentação exige uma dívida " + expected + ", mas a dívida é " + actual + ".");
        }
    }

    public static class SettlementExceedsOutstandingException extends BusinessRuleException {
        public SettlementExceedsOutstandingException() {
            super("DEBT_SETTLEMENT_EXCEEDS_OUTSTANDING", "A quitação passa do saldo devedor da dívida.");
        }
    }

    public static class DebtOperationRefusedException extends BusinessRuleException {
        public DebtOperationRefusedException(String code, String message) {
            super(code, message);
        }
    }

    public static class DebtCategoryNotAllowedException extends BusinessRuleException {
        public DebtCategoryNotAllowedException() {
            super("DEBT_CATEGORY_NOT_ALLOWED", "Só a dívida que você deve (PAYABLE) pode ter categoria de gasto.");
        }
    }

    public static class DebtReasonRequiredException extends BusinessRuleException {
        public DebtReasonRequiredException() {
            super("DEBT_REASON_REQUIRED", "O motivo é obrigatório.");
        }
    }

    public static class DebtCreditorInvalidException extends BusinessRuleException {
        public DebtCreditorInvalidException(String reason) {
            super("DEBT_CREDITOR_INVALID", "Credor da dívida inválido: " + reason);
        }
    }

    public static class DebtStateInconsistentException extends DomainException {
        public DebtStateInconsistentException(String reason) {
            super("DEBT_STATE_INCONSISTENT", "Estado gravado da dívida inconsistente: " + reason);
        }

        public DebtStateInconsistentException(String reason, Throwable cause) {
            super("DEBT_STATE_INCONSISTENT", "Estado gravado da dívida inconsistente: " + reason, cause);
        }
    }
}
