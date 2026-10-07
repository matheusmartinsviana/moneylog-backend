package com.moneylog.core.debt.exception;

import java.util.UUID;

import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.exception.NotFoundException;

/**
 * Exceções da feature pessoas e dívidas.
 */
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

    /** A descrição cabe na coluna {@code debts.description} (VARCHAR(200)). */
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

    /** Ex.: LOAN_GIVEN ligado a PAYABLE, DEBT_PAYMENT ligado a RECEIVABLE. */
    public static class DebtDirectionMismatchException extends BusinessRuleException {
        public DebtDirectionMismatchException(DebtDirection expected, DebtDirection actual) {
            super("DEBT_DIRECTION_MISMATCH", "A movimentação exige uma dívida " + expected + ", mas a dívida é " + actual + ".");
        }
    }

    /** Quitação acima do saldo devedor (D1 §4, invariante 7). */
    public static class SettlementExceedsOutstandingException extends BusinessRuleException {
        public SettlementExceedsOutstandingException() {
            super("DEBT_SETTLEMENT_EXCEEDS_OUTSTANDING", "A quitação passa do saldo devedor da dívida.");
        }
    }

    /**
     * Operação recusada pelo estado da dívida, com {@code code} estável: {@code DEBT_CLOSED},
     * {@code DEBT_HAS_SETTLEMENTS}, {@code DEBT_ALREADY_HAS_ORIGIN}, {@code DEBT_ALREADY_SETTLED},
     * {@code DEBT_HAS_ORIGIN_TRANSACTION}.
     */
    public static class DebtOperationRefusedException extends BusinessRuleException {
        public DebtOperationRefusedException(String code, String message) {
            super(code, message);
        }
    }

    /** Só a dívida {@code PAYABLE} nasce de consumo (S-137): "gasto que alguém pagou por você". */
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

    /** Estado gravado de uma dívida inconsistente ao reidratar ({@code Debt.restore}): dado corrompido, vira 500. */
    public static class DebtStateInconsistentException extends DomainException {
        public DebtStateInconsistentException(String reason) {
            super("DEBT_STATE_INCONSISTENT", "Estado gravado da dívida inconsistente: " + reason);
        }

        public DebtStateInconsistentException(String reason, Throwable cause) {
            super("DEBT_STATE_INCONSISTENT", "Estado gravado da dívida inconsistente: " + reason, cause);
        }
    }
}
