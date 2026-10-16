package com.moneylog.core.commitment.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.NotFoundException;

public final class CommitmentExceptions {

    private CommitmentExceptions() {
    }

    public static class CommitmentNotFoundException extends NotFoundException {
        public CommitmentNotFoundException(UUID id) {
            super("COMMITMENT_NOT_FOUND", "Compromisso não encontrado: " + id + ".");
        }
    }

    public static class InvalidCommitmentException extends BusinessRuleException {
        public InvalidCommitmentException(String reason) {
            super("COMMITMENT_INVALID", "Compromisso inválido: " + reason);
        }
    }

    public static class ScheduleItemNotFoundException extends NotFoundException {
        public ScheduleItemNotFoundException(UUID id, int position) {
            super("COMMITMENT_SCHEDULE_ITEM_NOT_FOUND", "O compromisso " + id + " não tem a parcela " + position + " no cronograma.");
        }
    }

    public static class ScheduleItemAlreadyPaidException extends BusinessRuleException {
        public ScheduleItemAlreadyPaidException(int position) {
            super("COMMITMENT_SCHEDULE_ITEM_ALREADY_PAID", "A parcela " + position + " do cronograma já está paga.");
        }
    }

    public static class SharesInvalidException extends BusinessRuleException {
        public SharesInvalidException(String reason) {
            super("COMMITMENT_SHARES_INVALID", "As cotas do item são inválidas: " + reason);
        }
    }

    public static class CommitmentPersonInvalidException extends BusinessRuleException {
        public CommitmentPersonInvalidException() {
            super("COMMITMENT_SHARES_INVALID", "Pessoa inválida nas cotas do item.");
        }
    }
}
