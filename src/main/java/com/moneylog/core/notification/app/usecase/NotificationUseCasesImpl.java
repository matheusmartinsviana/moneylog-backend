package com.moneylog.core.notification.app.usecase;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.notification.app.GenerationContextLoader;
import com.moneylog.core.notification.app.gateways.NotificationGateway;
import com.moneylog.core.notification.app.gateways.NotificationSettingsGateway;
import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSender.PushMessage;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.core.notification.app.gateways.ReminderGateway;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.ArchiveOldNotificationsUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.CompleteReminderUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.CountUnreadUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.CreateReminderUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.DailyMessageView;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.DispatchPushUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.GenerateNotificationsUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.GetDailyMessageUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.GetNotificationSettingsUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.GetPushStatusUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.ListNotificationsUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.ListRemindersUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.MarkAllNotificationsReadUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.MarkNotificationReadUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.PushStatus;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.ReminderCommand;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.SaveNotificationSettingsUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.SaveSettingsCommand;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.SnoozeReminderUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.SubscribeCommand;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.SubscribePushUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.UnsubscribePushUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.UpdateReminderUseCase;
import com.moneylog.core.notification.domain.DailyMessage;
import com.moneylog.core.notification.domain.GenerationContext;
import com.moneylog.core.notification.domain.Notification;
import com.moneylog.core.notification.domain.PushEndpointPolicy;
import com.moneylog.core.notification.domain.NotificationDraft;
import com.moneylog.core.notification.domain.NotificationGenerators;
import com.moneylog.core.notification.domain.NotificationKind;
import com.moneylog.core.notification.domain.NotificationPlanner;
import com.moneylog.core.notification.domain.NotificationSettings;
import com.moneylog.core.notification.domain.Reminder;
import com.moneylog.core.notification.exception.NotificationExceptions.InvalidPushSubscriptionException;
import com.moneylog.core.notification.exception.NotificationExceptions.NotificationNotFoundException;
import com.moneylog.core.notification.exception.NotificationExceptions.NotificationSettingsVersionConflictException;
import com.moneylog.core.notification.exception.NotificationExceptions.PushNotEnabledException;
import com.moneylog.core.notification.exception.NotificationExceptions.PushSubscriptionLimitException;
import com.moneylog.core.notification.exception.NotificationExceptions.PushSubscriptionNotFoundException;
import com.moneylog.core.notification.exception.NotificationExceptions.ReminderNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.TimeProvider;

/** Implementações dos casos de uso de notificações (T127 a T130). */
public final class NotificationUseCasesImpl {

    /** Lidas há mais de 90 dias saem da central (S-179). */
    public static final int RETENTION_DAYS = 90;
    public static final int MAX_PUSH_SUBSCRIPTIONS = 10;
    /** Valores financeiros que não vão no payload do push sem {@code push.includeAmounts=true}. */
    static final Set<String> AMOUNT_PARAMS = Set.of("amount", "total", "available", "committed7d");

    private NotificationUseCasesImpl() {
    }

    // ------------------------------------------------------------------ central

    public static final class ListNotifications implements ListNotificationsUseCase {
        private final NotificationGateway notifications;

        public ListNotifications(NotificationGateway notifications) {
            this.notifications = notifications;
        }

        @Override
        public Slice<Notification> handle(UUID userId, boolean unreadOnly, NotificationKind kind, PageQuery page) {
            return notifications.list(userId, unreadOnly, kind, page);
        }
    }

    public static final class CountUnread implements CountUnreadUseCase {
        private final NotificationGateway notifications;

        public CountUnread(NotificationGateway notifications) {
            this.notifications = notifications;
        }

        @Override
        public long handle(UUID userId) {
            return notifications.unreadCount(userId);
        }
    }

    public static final class MarkRead implements MarkNotificationReadUseCase {
        private final NotificationGateway notifications;
        private final TimeProvider time;

        public MarkRead(NotificationGateway notifications, TimeProvider time) {
            this.notifications = notifications;
            this.time = time;
        }

        @Override
        public void handle(UUID userId, UUID notificationId) {
            Notification n = notifications.find(userId, notificationId).orElseThrow(() -> new NotificationNotFoundException(notificationId));
            n.markRead(time.now());
            notifications.save(n);
        }
    }

    public static final class MarkAllRead implements MarkAllNotificationsReadUseCase {
        private final NotificationGateway notifications;
        private final TimeProvider time;

        public MarkAllRead(NotificationGateway notifications, TimeProvider time) {
            this.notifications = notifications;
            this.time = time;
        }

        @Override
        public int handle(UUID userId) {
            return notifications.markAllRead(userId, time.now());
        }
    }

    // ------------------------------------------------------------------ lembretes

    public static final class CreateReminder implements CreateReminderUseCase {
        private final ReminderGateway reminders;
        private final IdGenerator ids;
        private final TimeProvider time;

        public CreateReminder(ReminderGateway reminders, IdGenerator ids, TimeProvider time) {
            this.reminders = reminders;
            this.ids = ids;
            this.time = time;
        }

        @Override
        public Reminder handle(ReminderCommand c) {
            return reminders.save(Reminder.create(ids.next(), c.userId(), c.title(), c.remindOn(), c.remindAt(), c.note(), c.linkType(), c.linkId(),
                    time.now()));
        }
    }

    public static final class UpdateReminder implements UpdateReminderUseCase {
        private final ReminderGateway reminders;

        public UpdateReminder(ReminderGateway reminders) {
            this.reminders = reminders;
        }

        @Override
        public Reminder handle(UUID reminderId, ReminderCommand c) {
            Reminder reminder = reminders.find(c.userId(), reminderId).orElseThrow(() -> new ReminderNotFoundException(reminderId));
            reminder.update(c.title(), c.remindOn(), c.remindAt(), c.note(), c.linkType(), c.linkId());
            return reminders.save(reminder);
        }
    }

    public static final class ListReminders implements ListRemindersUseCase {
        private final ReminderGateway reminders;

        public ListReminders(ReminderGateway reminders) {
            this.reminders = reminders;
        }

        @Override
        public Slice<Reminder> handle(UUID userId, boolean includeDone, PageQuery page) {
            return reminders.list(userId, includeDone, page);
        }
    }

    public static final class CompleteReminder implements CompleteReminderUseCase {
        private final ReminderGateway reminders;
        private final TimeProvider time;

        public CompleteReminder(ReminderGateway reminders, TimeProvider time) {
            this.reminders = reminders;
            this.time = time;
        }

        @Override
        public Reminder handle(UUID userId, UUID reminderId) {
            Reminder reminder = reminders.find(userId, reminderId).orElseThrow(() -> new ReminderNotFoundException(reminderId));
            reminder.done(time.now());
            return reminders.save(reminder);
        }
    }

    public static final class SnoozeReminder implements SnoozeReminderUseCase {
        private final ReminderGateway reminders;

        public SnoozeReminder(ReminderGateway reminders) {
            this.reminders = reminders;
        }

        @Override
        public Reminder handle(UUID userId, UUID reminderId, LocalDate newDate) {
            Reminder reminder = reminders.find(userId, reminderId).orElseThrow(() -> new ReminderNotFoundException(reminderId));
            reminder.snooze(newDate);
            return reminders.save(reminder);
        }
    }

    // ------------------------------------------------------------------ configurações

    public static final class GetSettings implements GetNotificationSettingsUseCase {
        private final NotificationSettingsGateway settings;

        public GetSettings(NotificationSettingsGateway settings) {
            this.settings = settings;
        }

        @Override
        public NotificationSettings handle(UUID userId) {
            return settings.find(userId).orElseGet(() -> NotificationSettings.defaults(userId));
        }
    }

    public static final class SaveSettings implements SaveNotificationSettingsUseCase {
        private final NotificationSettingsGateway settings;

        public SaveSettings(NotificationSettingsGateway settings) {
            this.settings = settings;
        }

        @Override
        public NotificationSettings handle(SaveSettingsCommand c) {
            NotificationSettings current = settings.find(c.userId()).orElseGet(() -> NotificationSettings.defaults(c.userId()));
            if (current.version() != c.version()) {
                throw new NotificationSettingsVersionConflictException();
            }
            current.update(c.summaryEnabled(), c.summaryTime(), c.frequency(), c.kinds(), c.quietHours(), c.pushEnabled(), c.displayName());
            return settings.save(current, c.version()).orElseThrow(NotificationSettingsVersionConflictException::new);
        }
    }

    // ------------------------------------------------------------------ geração, retenção, push, mensagem diária

    public static final class GenerateNotifications implements GenerateNotificationsUseCase {
        private final GenerationContextLoader loader;
        private final NotificationGateway notifications;
        private final IdGenerator ids;
        private final TimeProvider time;

        public GenerateNotifications(GenerationContextLoader loader, NotificationGateway notifications, IdGenerator ids, TimeProvider time) {
            this.loader = loader;
            this.notifications = notifications;
            this.ids = ids;
            this.time = time;
        }

        @Override
        public int handle(UUID userId) {
            GenerationContext ctx = loader.load(userId);
            List<NotificationDraft> drafts = NotificationGenerators.generate(ctx);
            List<NotificationDraft> planned = NotificationPlanner.plan(userId, ctx.today(), drafts,
                    notifications.identitiesOn(userId, ctx.today()), notifications.countOn(userId, ctx.today()));
            // fora da janela de silêncio e com push ligado: elegível a push; senão só a central (S-175)
            boolean push = ctx.settings().pushEnabled() && !ctx.settings().quietHours().contains(ctx.localTime());
            int created = 0;
            for (NotificationDraft draft : planned) {
                if (notifications.insertIfAbsent(Notification.create(ids.next(), userId, draft, time.now(), push))) {
                    created++;
                }
            }
            return created;
        }
    }

    public static final class ArchiveOld implements ArchiveOldNotificationsUseCase {
        private final NotificationGateway notifications;
        private final TimeProvider time;

        public ArchiveOld(NotificationGateway notifications, TimeProvider time) {
            this.notifications = notifications;
            this.time = time;
        }

        @Override
        public int handle() {
            return notifications.archiveReadBefore(time.now().minus(Duration.ofDays(RETENTION_DAYS)));
        }
    }

    public static final class DispatchPush implements DispatchPushUseCase {
        static final int BATCH = 100;
        private final NotificationGateway notifications;
        private final PushSubscriptionGateway subscriptions;
        private final PushSender sender;
        private final TimeProvider time;
        private final boolean includeAmounts;

        public DispatchPush(NotificationGateway notifications, PushSubscriptionGateway subscriptions, PushSender sender, TimeProvider time,
                            boolean includeAmounts) {
            this.notifications = notifications;
            this.subscriptions = subscriptions;
            this.sender = sender;
            this.time = time;
            this.includeAmounts = includeAmounts;
        }

        @Override
        public int handle() {
            if (!sender.enabled()) {
                return 0;
            }
            int sent = 0;
            for (Notification n : notifications.pushPending(BATCH)) {
                boolean delivered = false;
                boolean retry = false;
                for (PushSubscription subscription : subscriptions.listByUser(n.userId())) {
                    PushSender.Result result = sender.send(subscription, message(n));
                    switch (result) {
                        case SENT -> delivered = true;
                        case GONE -> subscriptions.deleteByEndpoint(subscription.endpoint());
                        case RETRY_LATER -> retry = true;
                        case FAILED -> { }
                    }
                }
                if (delivered || !retry) {
                    // entregue, ou sem destino/falha definitiva: sai da fila (a central já tem a notificação)
                    n.pushSent(time.now());
                    notifications.save(n);
                }
                if (delivered) {
                    sent++;
                }
            }
            return sent;
        }

        private PushMessage message(Notification n) {
            Map<String, String> params = new java.util.TreeMap<>(n.params());
            if (!includeAmounts) {
                params.keySet().removeAll(AMOUNT_PARAMS);
            }
            return new PushMessage(n.id(), n.kind().name(), n.titleKey(), params);
        }
    }

    public static final class GetDailyMessage implements GetDailyMessageUseCase {
        private final GenerationContextLoader loader;
        private final TimeProvider time;

        public GetDailyMessage(GenerationContextLoader loader, TimeProvider time) {
            this.loader = loader;
            this.time = time;
        }

        @Override
        public DailyMessageView handle(UUID userId) {
            GenerationContext ctx = loader.load(userId);
            DailyMessage message = DailyMessage.of(ctx.settings().displayName(), ctx.available(), ctx.committed7d(), ctx.goals(), ctx.today());
            return new DailyMessageView(message, DailyMessageView.STATIC, time.now());
        }
    }

    // ------------------------------------------------------------------ push

    public static final class SubscribePush implements SubscribePushUseCase {
        private final PushSubscriptionGateway subscriptions;
        private final PushSender sender;
        private final IdGenerator ids;
        private final TimeProvider time;

        public SubscribePush(PushSubscriptionGateway subscriptions, PushSender sender, IdGenerator ids, TimeProvider time) {
            this.subscriptions = subscriptions;
            this.sender = sender;
            this.ids = ids;
            this.time = time;
        }

        @Override
        public PushSubscription handle(SubscribeCommand c) {
            if (!sender.enabled()) {
                throw new PushNotEnabledException();
            }
            if (!PushEndpointPolicy.isAllowed(c.endpoint())) {
                throw new InvalidPushSubscriptionException("o endpoint deve ser de um serviço de push do navegador (https, até 500 caracteres).");
            }
            if (blank(c.p256dh()) || blank(c.auth()) || c.p256dh().length() > 200 || c.auth().length() > 100) {
                throw new InvalidPushSubscriptionException("chaves p256dh e auth são obrigatórias.");
            }
            boolean known = subscriptions.listByUser(c.userId()).stream().anyMatch(s -> s.endpoint().equals(c.endpoint()));
            if (!known && subscriptions.count(c.userId()) >= MAX_PUSH_SUBSCRIPTIONS) {
                throw new PushSubscriptionLimitException(MAX_PUSH_SUBSCRIPTIONS);
            }
            String agent = c.userAgent() == null ? null : c.userAgent().length() > 200 ? c.userAgent().substring(0, 200) : c.userAgent();
            return subscriptions.upsert(new PushSubscription(ids.next(), c.userId(), c.endpoint(), c.p256dh(), c.auth(), agent, time.now()));
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }
    }

    public static final class UnsubscribePush implements UnsubscribePushUseCase {
        private final PushSubscriptionGateway subscriptions;

        public UnsubscribePush(PushSubscriptionGateway subscriptions) {
            this.subscriptions = subscriptions;
        }

        @Override
        public void handle(UUID userId, UUID subscriptionId) {
            if (!subscriptions.delete(userId, subscriptionId)) {
                throw new PushSubscriptionNotFoundException(subscriptionId);
            }
        }
    }

    public static final class PushStatusReader implements GetPushStatusUseCase {
        private final PushSender sender;

        public PushStatusReader(PushSender sender) {
            this.sender = sender;
        }

        @Override
        public PushStatus handle() {
            return new PushStatus(sender.enabled(), sender.enabled() ? sender.publicKey() : null);
        }
    }

    /** Instante usado nos testes para o corte de retenção. */
    static Instant cutoff(Instant now) {
        return now.minus(Duration.ofDays(RETENTION_DAYS));
    }
}
