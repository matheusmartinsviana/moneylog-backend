package com.moneylog.core.goal.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.goal.domain.GoalActionFrequency;
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

    /** Meta {@code METRIC} com alvo igual à medida atual: não há caminho a percorrer (ADR-016). */
    public static class GoalMetricTargetEqualsBaselineException extends BusinessRuleException {
        public GoalMetricTargetEqualsBaselineException() {
            super("GOAL_METRIC_TARGET_EQUALS_BASELINE", "O alvo é igual ao valor atual da medida; escolha um alvo diferente.");
        }
    }

    /** Alvo negativo numa meta {@code METRIC} (zero vale: dívida zerada, custo fixo zerado). */
    public static class GoalMetricTargetNegativeException extends BusinessRuleException {
        public GoalMetricTargetNegativeException() {
            super("GOAL_METRIC_TARGET_NEGATIVE", "O alvo da meta de medida não pode ser negativo.");
        }
    }

    /** Já existe meta {@code METRIC} ativa com a mesma medida e o mesmo alvo. */
    public static class GoalMetricDuplicateException extends ConflictException {
        public GoalMetricDuplicateException() {
            super("GOAL_METRIC_DUPLICATE", "Você já tem uma meta ativa para esta medida e este alvo.");
        }
    }

    /** Ação inexistente na meta. */
    public static class GoalActionNotFoundException extends NotFoundException {
        public GoalActionNotFoundException(UUID actionId) {
            super("GOAL_ACTION_NOT_FOUND", "Ação da meta não encontrada: " + actionId + ".");
        }
    }

    /** Mais ações do que o plano comporta. */
    public static class GoalActionLimitException extends BusinessRuleException {
        public GoalActionLimitException(int max) {
            super("GOAL_ACTION_LIMIT", "O plano da meta pode ter no máximo " + max + " ações.");
        }
    }

    /** Período do check-in fora do formato da frequência da ação. */
    public static class GoalCheckInPeriodInvalidException extends BusinessRuleException {
        public GoalCheckInPeriodInvalidException(GoalActionFrequency frequency) {
            super("GOAL_CHECKIN_PERIOD_INVALID", "Período inválido para ação " + frequency + " (MONTHLY: YYYY-MM; WEEKLY: YYYY-Www; ONE_TIME: ONCE).");
        }
    }

    /** Estado gravado de uma meta inconsistente ao reidratar: vira 500 (invariante interna). */
    public static class GoalStateInconsistentException extends DomainException {
        public GoalStateInconsistentException(String reason) {
            super("GOAL_STATE_INCONSISTENT", "Estado gravado da meta inconsistente: " + reason);
        }
    }
}
