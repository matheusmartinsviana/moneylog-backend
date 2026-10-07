package com.moneylog.infra.config;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.notification.app.GenerationContextLoader;
import com.moneylog.core.notification.app.gateways.NotificationGateway;
import com.moneylog.core.notification.app.gateways.NotificationSettingsGateway;
import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway;
import com.moneylog.core.notification.app.gateways.ReminderGateway;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.ArchiveOldNotificationsUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.CompleteReminderUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.CountUnreadUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.CreateReminderUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.DispatchPushUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.GenerateNotificationsUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.GetDailyMessageUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.GetNotificationSettingsUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.GetPushStatusUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.ListNotificationsUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.ListRemindersUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.MarkAllNotificationsReadUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.MarkNotificationReadUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.SaveNotificationSettingsUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.SnoozeReminderUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.SubscribePushUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.UnsubscribePushUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.UpdateReminderUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCasesImpl;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.notification.gateways.NotificationGatewayImpl;
import com.moneylog.infra.notification.gateways.NotificationSettingsGatewayImpl;
import com.moneylog.infra.notification.gateways.PushSubscriptionGatewayImpl;
import com.moneylog.infra.notification.gateways.ReminderGatewayImpl;
import com.moneylog.infra.notification.jobs.NotificationJob;
import com.moneylog.infra.notification.push.NoopPushSender;
import com.moneylog.infra.notification.push.WebPushSender;

/**
 * Ligação das notificações (Etapa 7): gateways em JDBC, casos de uso transacionais, o job idempotente e o push atrás de {@link PushSender}
 * ({@code NoopPushSender} por padrão; {@code WebPushSender} só com {@code moneylog.push.enabled=true} e as chaves VAPID, senão o app não sobe).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class NotificationConfig {

    @Bean
    NotificationGateway notificationGateway(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new NotificationGatewayImpl(jdbc, mapper);
    }

    @Bean
    ReminderGateway reminderGateway(JdbcTemplate jdbc) {
        return new ReminderGatewayImpl(jdbc);
    }

    @Bean
    NotificationSettingsGateway notificationSettingsGateway(JdbcTemplate jdbc) {
        return new NotificationSettingsGatewayImpl(jdbc);
    }

    @Bean
    PushSubscriptionGateway pushSubscriptionGateway(JdbcTemplate jdbc) {
        return new PushSubscriptionGatewayImpl(jdbc);
    }

    /** S-173: o app NÃO sobe com push ligado e sem chaves (erro claro), como o {@code JWT_SECRET}. */
    @Bean
    PushSender pushSender(@Value("${moneylog.push.enabled:false}") boolean enabled, @Value("${moneylog.push.vapid-public:}") String publicKey,
                          @Value("${moneylog.push.vapid-private:}") String privateKey, @Value("${moneylog.push.vapid-subject:}") String subject,
                          @Value("${moneylog.push.ttl-seconds:86400}") int ttlSeconds, ObjectMapper mapper, Clock clock) {
        if (!enabled) {
            return new NoopPushSender();
        }
        if (publicKey.isBlank() || privateKey.isBlank() || subject.isBlank()) {
            throw new IllegalStateException("moneylog.push.enabled=true exige MONEYLOG_PUSH_VAPID_PUBLIC, MONEYLOG_PUSH_VAPID_PRIVATE e "
                    + "MONEYLOG_PUSH_VAPID_SUBJECT (veja .env.example). Sem as chaves o push fica desligado: moneylog.push.enabled=false.");
        }
        return new WebPushSender(publicKey, privateKey, subject, mapper, clock, ttlSeconds);
    }

    @Bean
    GenerationContextLoader generationContextLoader(FinancialRecordsGateway records, CommitmentSourcesGateway sources, GoalGateway goals,
                                                    ReminderGateway reminders, NotificationSettingsGateway settings, TimeProvider time) {
        return new GenerationContextLoader(records, sources, goals, reminders, settings, time);
    }

    // ---- central

    @Bean
    ListNotificationsUseCase listNotificationsUseCase(NotificationGateway notifications, PlatformTransactionManager tm) {
        return TransactionalProxies.readOnly(ListNotificationsUseCase.class, new NotificationUseCasesImpl.ListNotifications(notifications), tm);
    }

    @Bean
    CountUnreadUseCase countUnreadUseCase(NotificationGateway notifications, PlatformTransactionManager tm) {
        return TransactionalProxies.readOnly(CountUnreadUseCase.class, new NotificationUseCasesImpl.CountUnread(notifications), tm);
    }

    @Bean
    MarkNotificationReadUseCase markNotificationReadUseCase(NotificationGateway notifications, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(MarkNotificationReadUseCase.class, new NotificationUseCasesImpl.MarkRead(notifications, time), tm);
    }

    @Bean
    MarkAllNotificationsReadUseCase markAllNotificationsReadUseCase(NotificationGateway notifications, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(MarkAllNotificationsReadUseCase.class, new NotificationUseCasesImpl.MarkAllRead(notifications, time), tm);
    }

    // ---- lembretes

    @Bean
    CreateReminderUseCase createReminderUseCase(ReminderGateway reminders, IdGenerator ids, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateReminderUseCase.class, new NotificationUseCasesImpl.CreateReminder(reminders, ids, time), tm);
    }

    @Bean
    UpdateReminderUseCase updateReminderUseCase(ReminderGateway reminders, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdateReminderUseCase.class, new NotificationUseCasesImpl.UpdateReminder(reminders), tm);
    }

    @Bean
    ListRemindersUseCase listRemindersUseCase(ReminderGateway reminders, PlatformTransactionManager tm) {
        return TransactionalProxies.readOnly(ListRemindersUseCase.class, new NotificationUseCasesImpl.ListReminders(reminders), tm);
    }

    @Bean
    CompleteReminderUseCase completeReminderUseCase(ReminderGateway reminders, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CompleteReminderUseCase.class, new NotificationUseCasesImpl.CompleteReminder(reminders, time), tm);
    }

    @Bean
    SnoozeReminderUseCase snoozeReminderUseCase(ReminderGateway reminders, PlatformTransactionManager tm) {
        return TransactionalProxies.required(SnoozeReminderUseCase.class, new NotificationUseCasesImpl.SnoozeReminder(reminders), tm);
    }

    // ---- configurações

    @Bean
    GetNotificationSettingsUseCase getNotificationSettingsUseCase(NotificationSettingsGateway settings, PlatformTransactionManager tm) {
        return TransactionalProxies.readOnly(GetNotificationSettingsUseCase.class, new NotificationUseCasesImpl.GetSettings(settings), tm);
    }

    @Bean
    SaveNotificationSettingsUseCase saveNotificationSettingsUseCase(NotificationSettingsGateway settings, PlatformTransactionManager tm) {
        return TransactionalProxies.required(SaveNotificationSettingsUseCase.class, new NotificationUseCasesImpl.SaveSettings(settings), tm);
    }

    // ---- geração, push, mensagem diária

    @Bean
    GenerateNotificationsUseCase generateNotificationsUseCase(GenerationContextLoader loader, NotificationGateway notifications, IdGenerator ids,
                                                              TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(GenerateNotificationsUseCase.class,
                new NotificationUseCasesImpl.GenerateNotifications(loader, notifications, ids, time), tm);
    }

    @Bean
    ArchiveOldNotificationsUseCase archiveOldNotificationsUseCase(NotificationGateway notifications, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(ArchiveOldNotificationsUseCase.class, new NotificationUseCasesImpl.ArchiveOld(notifications, time), tm);
    }

    @Bean
    DispatchPushUseCase dispatchPushUseCase(NotificationGateway notifications, PushSubscriptionGateway subscriptions, PushSender sender,
                                            TimeProvider time, @Value("${moneylog.push.include-amounts:false}") boolean includeAmounts) {
        // sem transação ao redor do envio de rede: cada gravação do gateway é curta; a falha de um envio não segura conexão do pool
        return new NotificationUseCasesImpl.DispatchPush(notifications, subscriptions, sender, time, includeAmounts);
    }

    @Bean
    GetDailyMessageUseCase getDailyMessageUseCase(GenerationContextLoader loader, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetDailyMessageUseCase.class, new NotificationUseCasesImpl.GetDailyMessage(loader, time), tm);
    }

    @Bean
    SubscribePushUseCase subscribePushUseCase(PushSubscriptionGateway subscriptions, PushSender sender, IdGenerator ids, TimeProvider time,
                                              PlatformTransactionManager tm) {
        return TransactionalProxies.required(SubscribePushUseCase.class, new NotificationUseCasesImpl.SubscribePush(subscriptions, sender, ids, time), tm);
    }

    @Bean
    UnsubscribePushUseCase unsubscribePushUseCase(PushSubscriptionGateway subscriptions, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UnsubscribePushUseCase.class, new NotificationUseCasesImpl.UnsubscribePush(subscriptions), tm);
    }

    @Bean
    GetPushStatusUseCase getPushStatusUseCase(PushSender sender) {
        return new NotificationUseCasesImpl.PushStatusReader(sender);
    }

    @Bean
    @ConditionalOnProperty(name = "moneylog.notifications.job.enabled", havingValue = "true", matchIfMissing = true)
    NotificationJob notificationJob(NotificationGateway notifications, GenerateNotificationsUseCase generate, DispatchPushUseCase dispatch,
                                    ArchiveOldNotificationsUseCase archive) {
        return new NotificationJob(notifications, generate, dispatch, archive);
    }
}
