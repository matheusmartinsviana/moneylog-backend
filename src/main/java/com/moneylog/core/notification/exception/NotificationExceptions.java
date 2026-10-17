package com.moneylog.core.notification.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.NotFoundException;

public final class NotificationExceptions {

    private NotificationExceptions() {
    }

    public static class NotificationNotFoundException extends NotFoundException {
        public NotificationNotFoundException(UUID id) {
            super("NOTIFICATION_NOT_FOUND", "Notificação não encontrada: " + id);
        }
    }

    public static class ReminderNotFoundException extends NotFoundException {
        public ReminderNotFoundException(UUID id) {
            super("REMINDER_NOT_FOUND", "Lembrete não encontrado: " + id);
        }
    }

    public static class PushSubscriptionNotFoundException extends NotFoundException {
        public PushSubscriptionNotFoundException(UUID id) {
            super("PUSH_SUBSCRIPTION_NOT_FOUND", "Assinatura de push não encontrada: " + id);
        }
    }

    public static class InvalidReminderException extends BusinessRuleException {
        public InvalidReminderException(String reason) {
            super("REMINDER_INVALID", "Lembrete inválido: " + reason);
        }
    }

    public static class ReminderAlreadyDoneException extends BusinessRuleException {
        public ReminderAlreadyDoneException(UUID id) {
            super("REMINDER_ALREADY_DONE", "O lembrete " + id + " já foi concluído.");
        }
    }

    public static class InvalidNotificationSettingsException extends BusinessRuleException {
        public InvalidNotificationSettingsException(String reason) {
            super("NOTIFICATION_SETTINGS_INVALID", "Configuração de notificações inválida: " + reason);
        }
    }

    public static class NotificationThresholdInvalidException extends BusinessRuleException {
        public NotificationThresholdInvalidException(String reason) {
            super("NOTIFICATION_THRESHOLD_INVALID", "Parâmetro de alerta inválido: " + reason);
        }
    }

    public static class NotificationSettingsVersionConflictException extends ConflictException {
        public NotificationSettingsVersionConflictException() {
            super("VERSION_CONFLICT", "As configurações foram alteradas por outra requisição. Recarregue e tente de novo.");
        }
    }

    public static class PushSubscriptionLimitException extends BusinessRuleException {
        public PushSubscriptionLimitException(int max) {
            super("PUSH_SUBSCRIPTION_LIMIT", "Limite de " + max + " dispositivos com notificação push por usuário.");
        }
    }

    public static class InvalidPushSubscriptionException extends BusinessRuleException {
        public InvalidPushSubscriptionException(String reason) {
            super("PUSH_SUBSCRIPTION_INVALID", "Assinatura de push inválida: " + reason);
        }
    }

    public static class PushNotEnabledException extends BusinessRuleException {
        public PushNotEnabledException() {
            super("PUSH_NOT_ENABLED", "As notificações no dispositivo ainda não estão ativadas neste servidor.");
        }
    }
}
