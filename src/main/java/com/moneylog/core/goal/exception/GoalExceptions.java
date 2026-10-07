package com.moneylog.core.goal.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.exception.NotFoundException;
import com.moneylog.core.sk.money.Money;

/** Exceções da feature meta (D1 §2.9). */
public final class GoalExceptions {

    private GoalExceptions() {
    }

    /** Meta inexistente ou de outro usuário (não revela existência). */
    public static class GoalNotFoundException extends NotFoundException {
        public GoalNotFoundException(UUID goalId) {
            super("GOAL_NOT_FOUND", "Meta não encontrada: " + goalId + ".");
        }
    }

    /** Meta arquivada não recebe reserva nem edição (desarquive primeiro). */
    public static class GoalArchivedException extends BusinessRuleException {
        public GoalArchivedException() {
            super("GOAL_ARCHIVED", "A meta está arquivada e não aceita reservas nem edição.");
        }
    }

    public static class GoalAlreadyArchivedException extends BusinessRuleException {
        public GoalAlreadyArchivedException() {
            super("GOAL_ALREADY_ARCHIVED", "A meta já está arquivada.");
        }
    }

    public static class GoalNotArchivedException extends BusinessRuleException {
        public GoalNotArchivedException() {
            super("GOAL_NOT_ARCHIVED", "A meta não está arquivada.");
        }
    }

    /** A reserva só vale em conta {@code CASH} ou {@code INVESTMENT} em BRL (S-147). */
    public static class GoalReservationAccountNotEligibleException extends BusinessRuleException {
        public GoalReservationAccountNotEligibleException(String reason) {
            super("GOAL_RESERVATION_ACCOUNT_NOT_ELIGIBLE", "A conta não pode receber reserva de meta: " + reason);
        }
    }

    /** Liberar mais do que a meta tem reservado naquela conta (em qualquer data). */
    public static class GoalReleaseExceedsReservedException extends BusinessRuleException {
        public GoalReleaseExceedsReservedException(Money reserved, Money released) {
            super("GOAL_RELEASE_EXCEEDS_RESERVED",
                    "A liberação de " + released.abs() + " passa do que a meta tem reservado na conta (" + reserved + ").");
        }
    }

    public static class GoalFieldRequiredException extends BusinessRuleException {
        public GoalFieldRequiredException(String field) {
            super("GOAL_FIELD_REQUIRED", "Campo obrigatório da meta ausente: " + field + ".");
        }
    }

    public static class GoalNameRequiredException extends BusinessRuleException {
        public GoalNameRequiredException() {
            super("GOAL_NAME_REQUIRED", "O nome da meta é obrigatório.");
        }
    }

    public static class GoalNameTooLongException extends BusinessRuleException {
        public GoalNameTooLongException(int max) {
            super("GOAL_NAME_TOO_LONG", "O nome da meta pode ter no máximo " + max + " caracteres.");
        }
    }

    /** Descrição (500) ou nota da reserva (200) acima do limite da coluna. */
    public static class GoalTextTooLongException extends BusinessRuleException {
        public GoalTextTooLongException(String field, int max) {
            super("GOAL_TEXT_TOO_LONG", "O campo '" + field + "' da meta pode ter no máximo " + max + " caracteres.");
        }
    }

    public static class GoalTargetNotPositiveException extends BusinessRuleException {
        public GoalTargetNotPositiveException() {
            super("GOAL_TARGET_NOT_POSITIVE", "O valor da meta precisa ser maior que zero.");
        }
    }

    public static class GoalReservationAmountZeroException extends BusinessRuleException {
        public GoalReservationAmountZeroException() {
            super("GOAL_RESERVATION_AMOUNT_ZERO", "O valor da reserva não pode ser zero (positivo reserva, negativo libera).");
        }
    }

    /** Reservar numa meta de quitar dívidas (ela não tem reservas). */
    public static class GoalKindNotReservableException extends BusinessRuleException {
        public GoalKindNotReservableException() {
            super("GOAL_KIND_NOT_RESERVABLE", "A meta de quitar dívidas não recebe reservas: o progresso vem do total de dívidas.");
        }
    }

    /** Já existe uma meta de quitar dívidas ativa (P8: uma por usuário). */
    public static class DebtPayoffGoalExistsException extends BusinessRuleException {
        public DebtPayoffGoalExistsException() {
            super("DEBT_PAYOFF_GOAL_EXISTS", "Você já tem uma meta de quitar dívidas ativa; arquive-a para criar outra.");
        }
    }

    /** Confirmar "cadastrei tudo" com total de dívidas zero: não há o que quitar. */
    public static class DebtPayoffNothingToBaselineException extends BusinessRuleException {
        public DebtPayoffNothingToBaselineException() {
            super("DEBT_PAYOFF_NOTHING_TO_BASELINE", "Não há dívidas cadastradas para ancorar a meta; cadastre o que você deve primeiro.");
        }
    }

    /** A operação só vale para a meta de quitar dívidas (ex.: baseline numa meta de guardar dinheiro). */
    public static class GoalKindMismatchException extends BusinessRuleException {
        public GoalKindMismatchException(String expected) {
            super("GOAL_KIND_MISMATCH", "Esta operação só vale para meta do tipo " + expected + ".");
        }
    }

    /** Estado gravado de uma meta inconsistente ao reidratar: vira 500 (invariante interna). */
    public static class GoalStateInconsistentException extends DomainException {
        public GoalStateInconsistentException(String reason) {
            super("GOAL_STATE_INCONSISTENT", "Estado gravado da meta inconsistente: " + reason);
        }
    }
}
