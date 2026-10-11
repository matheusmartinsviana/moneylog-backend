package com.moneylog.core.goal.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.goal.domain.GoalActionFrequency;
import com.moneylog.core.sk.exception.NotFoundException;
import com.moneylog.core.sk.money.Money;

public final class GoalExceptions {

    private GoalExceptions() {
    }

    public static class GoalNotFoundException extends NotFoundException {
        public GoalNotFoundException(UUID goalId) {
            super("GOAL_NOT_FOUND", "Meta não encontrada: " + goalId + ".");
        }
    }

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

    public static class GoalReservationAccountNotEligibleException extends BusinessRuleException {
        public GoalReservationAccountNotEligibleException(String reason) {
            super("GOAL_RESERVATION_ACCOUNT_NOT_ELIGIBLE", "A conta não pode receber reserva de meta: " + reason);
        }
    }

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

    public static class GoalKindNotReservableException extends BusinessRuleException {
        public GoalKindNotReservableException() {
            super("GOAL_KIND_NOT_RESERVABLE", "A meta de quitar dívidas não recebe reservas: o progresso vem do total de dívidas.");
        }
    }

    public static class DebtPayoffGoalExistsException extends BusinessRuleException {
        public DebtPayoffGoalExistsException() {
            super("DEBT_PAYOFF_GOAL_EXISTS", "Você já tem uma meta de quitar dívidas ativa; arquive-a para criar outra.");
        }
    }

    public static class DebtPayoffNothingToBaselineException extends BusinessRuleException {
        public DebtPayoffNothingToBaselineException() {
            super("DEBT_PAYOFF_NOTHING_TO_BASELINE", "Não há dívidas cadastradas para ancorar a meta; cadastre o que você deve primeiro.");
        }
    }

    public static class GoalKindMismatchException extends BusinessRuleException {
        public GoalKindMismatchException(String expected) {
            super("GOAL_KIND_MISMATCH", "Esta operação só vale para meta do tipo " + expected + ".");
        }
    }

    public static class GoalMetricTargetEqualsBaselineException extends BusinessRuleException {
        public GoalMetricTargetEqualsBaselineException() {
            super("GOAL_METRIC_TARGET_EQUALS_BASELINE", "O alvo é igual ao valor atual da medida; escolha um alvo diferente.");
        }
    }

    public static class GoalMetricTargetNegativeException extends BusinessRuleException {
        public GoalMetricTargetNegativeException() {
            super("GOAL_METRIC_TARGET_NEGATIVE", "O alvo da meta de medida não pode ser negativo.");
        }
    }

    public static class GoalMetricDuplicateException extends ConflictException {
        public GoalMetricDuplicateException() {
            super("GOAL_METRIC_DUPLICATE", "Você já tem uma meta ativa para esta medida e este alvo.");
        }
    }

    public static class GoalActionNotFoundException extends NotFoundException {
        public GoalActionNotFoundException(UUID actionId) {
            super("GOAL_ACTION_NOT_FOUND", "Ação da meta não encontrada: " + actionId + ".");
        }
    }

    public static class GoalActionLimitException extends BusinessRuleException {
        public GoalActionLimitException(int max) {
            super("GOAL_ACTION_LIMIT", "O plano da meta pode ter no máximo " + max + " ações.");
        }
    }

    public static class GoalCheckInPeriodInvalidException extends BusinessRuleException {
        public GoalCheckInPeriodInvalidException(GoalActionFrequency frequency) {
            super("GOAL_CHECKIN_PERIOD_INVALID", "Período inválido para ação " + frequency + " (MONTHLY: YYYY-MM; WEEKLY: YYYY-Www; ONE_TIME: ONCE).");
        }
    }

    public static class GoalPhotoNotFoundException extends NotFoundException {
        public GoalPhotoNotFoundException(UUID photoId) {
            super("GOAL_PHOTO_NOT_FOUND", "Foto da meta não encontrada: " + photoId + ".");
        }
    }

    public static class GoalPhotoTooLargeException extends BusinessRuleException {
        public GoalPhotoTooLargeException(long maxBytes) {
            super("GOAL_PHOTO_TOO_LARGE", "A foto passa do limite de " + (maxBytes / (1024 * 1024)) + " MB.");
        }
    }

    public static class GoalPhotoUnsupportedTypeException extends BusinessRuleException {
        public GoalPhotoUnsupportedTypeException() {
            super("GOAL_PHOTO_UNSUPPORTED_TYPE", "Envie uma foto em JPEG ou PNG.");
        }
    }

    public static class GoalPhotoInvalidImageException extends BusinessRuleException {
        public GoalPhotoInvalidImageException() {
            super("GOAL_PHOTO_INVALID_IMAGE", "Não foi possível ler a imagem. Tente outra foto.");
        }
    }

    public static class GoalPhotoDimensionsExceededException extends BusinessRuleException {
        public GoalPhotoDimensionsExceededException() {
            super("GOAL_PHOTO_DIMENSIONS_EXCEEDED", "A foto tem dimensões grandes demais.");
        }
    }

    public static class GoalPhotoLimitReachedException extends BusinessRuleException {
        public GoalPhotoLimitReachedException(int max) {
            super("GOAL_PHOTO_LIMIT_REACHED", "A meta pode ter no máximo " + max + " fotos.");
        }
    }

    public static class GoalPhotoQuotaExceededException extends BusinessRuleException {
        public GoalPhotoQuotaExceededException() {
            super("GOAL_PHOTO_QUOTA_EXCEEDED", "Você chegou ao limite de fotos guardadas. Remova alguma para enviar outra.");
        }
    }

    public static class GoalPhotosKindNotSupportedException extends BusinessRuleException {
        public GoalPhotosKindNotSupportedException() {
            super("GOAL_PHOTOS_KIND_NOT_SUPPORTED", "Só a meta de guardar dinheiro aceita fotos.");
        }
    }

    public static class GoalFeaturedKindNotSupportedException extends BusinessRuleException {
        public GoalFeaturedKindNotSupportedException() {
            super("GOAL_FEATURED_KIND_NOT_SUPPORTED", "Só a meta de guardar dinheiro pode ficar em destaque.");
        }
    }

    public static class GoalPhotosOrderMismatchException extends BusinessRuleException {
        public GoalPhotosOrderMismatchException() {
            super("GOAL_PHOTOS_ORDER_MISMATCH", "A nova ordem precisa ter todas as fotos da meta, sem repetir nenhuma.");
        }
    }

    public static class GoalStateInconsistentException extends DomainException {
        public GoalStateInconsistentException(String reason) {
            super("GOAL_STATE_INCONSISTENT", "Estado gravado da meta inconsistente: " + reason);
        }
    }
}
