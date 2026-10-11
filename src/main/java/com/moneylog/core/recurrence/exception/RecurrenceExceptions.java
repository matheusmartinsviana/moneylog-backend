package com.moneylog.core.recurrence.exception;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.exception.NotFoundException;

public final class RecurrenceExceptions {

    private RecurrenceExceptions() {
    }

    public static class RecurrenceNotFoundException extends NotFoundException {
        public RecurrenceNotFoundException(UUID id) {
            super("RECURRENCE_NOT_FOUND", "Recorrência não encontrada: " + id + ".");
        }
    }

    public static class InvalidRecurrenceException extends BusinessRuleException {
        public InvalidRecurrenceException(String reason) {
            super("RECURRENCE_INVALID", "Recorrência inválida: " + reason);
        }
    }

    public static class AccountRequiredException extends BusinessRuleException {
        public AccountRequiredException() {
            super("RECURRENCE_ACCOUNT_REQUIRED",
                    "A recorrência está com a conta a definir: informe a conta e o meio de pagamento ao confirmar a ocorrência.");
        }
    }

    public static class OccurrenceAlreadyRealizedException extends BusinessRuleException {
        public OccurrenceAlreadyRealizedException(LocalDate date) {
            super("RECURRENCE_OCCURRENCE_ALREADY_REALIZED", "A ocorrência de " + date + " já foi realizada.");
        }
    }

    public static class OccurrenceSkippedException extends BusinessRuleException {
        public OccurrenceSkippedException(LocalDate date) {
            super("RECURRENCE_OCCURRENCE_SKIPPED", "A ocorrência de " + date + " foi pulada.");
        }
    }

    public static class NotAnOccurrenceException extends BusinessRuleException {
        public NotAnOccurrenceException(LocalDate date) {
            super("RECURRENCE_NOT_AN_OCCURRENCE", "A data " + date + " não é uma ocorrência desta recorrência.");
        }
    }

    public static class RecurrenceInactiveException extends BusinessRuleException {
        public RecurrenceInactiveException() {
            super("RECURRENCE_INACTIVE", "A recorrência está desativada: não é possível realizar nem pular ocorrências.");
        }
    }

    public static class RecurrenceEndedException extends BusinessRuleException {
        public RecurrenceEndedException() {
            super("RECURRENCE_ENDED", "A recorrência já está encerrada: informe um novo endDate para reabri-la, ou crie outra.");
        }
    }

    public static class RecurrenceReferenceMismatchException extends BusinessRuleException {
        public RecurrenceReferenceMismatchException(String field) {
            super("RECURRENCE_REFERENCE_MISMATCH", "A " + field + " informada não é a do modelo da recorrência.");
        }
    }

    public static class RecurrenceStateInconsistentException extends DomainException {
        public RecurrenceStateInconsistentException(String reason) {
            super("RECURRENCE_STATE_INCONSISTENT", "Estado gravado da recorrência inconsistente: " + reason);
        }

        public RecurrenceStateInconsistentException(String reason, Throwable cause) {
            super("RECURRENCE_STATE_INCONSISTENT", "Estado gravado da recorrência inconsistente: " + reason, cause);
        }
    }
}
