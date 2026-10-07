package com.moneylog.core.notification.app.usecase;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.core.notification.domain.DailyMessage;
import com.moneylog.core.notification.domain.Notification;
import com.moneylog.core.notification.domain.NotificationKind;
import com.moneylog.core.notification.domain.NotificationSettings;
import com.moneylog.core.notification.domain.QuietHours;
import com.moneylog.core.notification.domain.Reminder;
import com.moneylog.core.notification.domain.SummaryFrequency;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

/** Casos de uso de notificações (Etapa 7). Todos filtram pelo usuário do token. */
public interface NotificationUseCases {

    interface ListNotificationsUseCase {
        Slice<Notification> handle(UUID userId, boolean unreadOnly, NotificationKind kind, PageQuery page);
    }

    interface CountUnreadUseCase {
        long handle(UUID userId);
    }

    interface MarkNotificationReadUseCase {
        /** Idempotente. */
        void handle(UUID userId, UUID notificationId);
    }

    interface MarkAllNotificationsReadUseCase {
        int handle(UUID userId);
    }

    // ---- lembretes

    record ReminderCommand(UUID userId, String title, LocalDate remindOn, LocalTime remindAt, String note, String linkType, UUID linkId) {
    }

    interface CreateReminderUseCase {
        Reminder handle(ReminderCommand command);
    }

    interface UpdateReminderUseCase {
        Reminder handle(UUID reminderId, ReminderCommand command);
    }

    interface ListRemindersUseCase {
        Slice<Reminder> handle(UUID userId, boolean includeDone, PageQuery page);
    }

    interface CompleteReminderUseCase {
        Reminder handle(UUID userId, UUID reminderId);
    }

    /** Adia para {@code newDate} (a data mínima é decidida pelo chamador: amanhã, +N dias). */
    interface SnoozeReminderUseCase {
        Reminder handle(UUID userId, UUID reminderId, LocalDate newDate);
    }

    // ---- configurações

    interface GetNotificationSettingsUseCase {
        NotificationSettings handle(UUID userId);
    }

    record SaveSettingsCommand(UUID userId, boolean summaryEnabled, LocalTime summaryTime, SummaryFrequency frequency,
                               Map<NotificationKind, Boolean> kinds, QuietHours quietHours, boolean pushEnabled, String displayName, long version) {
    }

    interface SaveNotificationSettingsUseCase {
        /** {@code 409 VERSION_CONFLICT} se a versão não é a atual. */
        NotificationSettings handle(SaveSettingsCommand command);
    }

    // ---- geração e jobs

    interface GenerateNotificationsUseCase {
        /** Gera as notificações do dia do usuário (idempotente); devolve quantas foram criadas agora. */
        int handle(UUID userId);
    }

    interface ArchiveOldNotificationsUseCase {
        /** Arquiva as lidas há mais de 90 dias (S-179); devolve quantas. */
        int handle();
    }

    interface DispatchPushUseCase {
        /** Envia o push das notificações elegíveis; devolve quantas saíram. Sem push ligado, não faz nada. */
        int handle();
    }

    interface GetDailyMessageUseCase {
        DailyMessageView handle(UUID userId);
    }

    record DailyMessageView(DailyMessage message, String source, Instant generatedAt) {
        public static final String STATIC = "STATIC";
    }

    // ---- push

    record SubscribeCommand(UUID userId, String endpoint, String p256dh, String auth, String userAgent) {
    }

    interface SubscribePushUseCase {
        PushSubscription handle(SubscribeCommand command);
    }

    interface UnsubscribePushUseCase {
        void handle(UUID userId, UUID subscriptionId);
    }

    /** Chave VAPID pública e se o push está ativo neste servidor (o front decide o fluxo). */
    interface GetPushStatusUseCase {
        PushStatus handle();
    }

    record PushStatus(boolean enabled, String publicKey) {
    }
}
