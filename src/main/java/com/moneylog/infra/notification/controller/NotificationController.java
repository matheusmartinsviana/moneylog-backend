package com.moneylog.infra.notification.controller;

import java.net.URI;
import java.time.LocalTime;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.moneylog.core.notification.app.usecase.NotificationUseCases.CompleteReminderUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.CountUnreadUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.CreateReminderUseCase;
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
import com.moneylog.core.notification.domain.NotificationKind;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.notification.controller.NotificationDtos.DailyMessageResponse;
import com.moneylog.infra.notification.controller.NotificationDtos.NotificationResponse;
import com.moneylog.infra.notification.controller.NotificationDtos.PushConfigResponse;
import com.moneylog.infra.notification.controller.NotificationDtos.PushSubscriptionResponse;
import com.moneylog.infra.notification.controller.NotificationDtos.ReminderInput;
import com.moneylog.infra.notification.controller.NotificationDtos.ReminderResponse;
import com.moneylog.infra.notification.controller.NotificationDtos.SettingsDto;
import com.moneylog.infra.notification.controller.NotificationDtos.SnoozeInput;
import com.moneylog.infra.notification.controller.NotificationDtos.SubscriptionInput;
import com.moneylog.infra.notification.controller.NotificationDtos.UnreadCount;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.SliceResponse;

/**
 * Central de notificações, lembretes, configurações, push e mensagem diária (Etapa 7). A central dentro do app é o canal garantido; o push fica
 * preparado e desligado. Todo endpoint filtra pelo usuário do token e nenhuma resposta é cacheada.
 */
@RestController
public class NotificationController {

    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final ListNotificationsUseCase list;
    private final CountUnreadUseCase unread;
    private final MarkNotificationReadUseCase markRead;
    private final MarkAllNotificationsReadUseCase markAll;
    private final CreateReminderUseCase createReminder;
    private final UpdateReminderUseCase updateReminder;
    private final ListRemindersUseCase listReminders;
    private final CompleteReminderUseCase completeReminder;
    private final SnoozeReminderUseCase snoozeReminder;
    private final GetNotificationSettingsUseCase getSettings;
    private final SaveNotificationSettingsUseCase saveSettings;
    private final SubscribePushUseCase subscribe;
    private final UnsubscribePushUseCase unsubscribe;
    private final GetPushStatusUseCase pushStatus;
    private final GetDailyMessageUseCase dailyMessage;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public NotificationController(ListNotificationsUseCase list, CountUnreadUseCase unread, MarkNotificationReadUseCase markRead,
                                  MarkAllNotificationsReadUseCase markAll, CreateReminderUseCase createReminder, UpdateReminderUseCase updateReminder,
                                  ListRemindersUseCase listReminders, CompleteReminderUseCase completeReminder, SnoozeReminderUseCase snoozeReminder,
                                  GetNotificationSettingsUseCase getSettings, SaveNotificationSettingsUseCase saveSettings,
                                  SubscribePushUseCase subscribe, UnsubscribePushUseCase unsubscribe, GetPushStatusUseCase pushStatus,
                                  GetDailyMessageUseCase dailyMessage, TimeProvider time) {
        this.list = list;
        this.unread = unread;
        this.markRead = markRead;
        this.markAll = markAll;
        this.createReminder = createReminder;
        this.updateReminder = updateReminder;
        this.listReminders = listReminders;
        this.completeReminder = completeReminder;
        this.snoozeReminder = snoozeReminder;
        this.getSettings = getSettings;
        this.saveSettings = saveSettings;
        this.subscribe = subscribe;
        this.unsubscribe = unsubscribe;
        this.pushStatus = pushStatus;
        this.dailyMessage = dailyMessage;
        this.time = time;
    }

    // ------------------------------------------------------------------ central

    /** {@code status=UNREAD} (padrão) ou {@code ALL}. */
    @GetMapping("/v1/notifications")
    public ResponseEntity<SliceResponse<NotificationResponse>> notifications(@CurrentUser UUID userId,
                                                                             @RequestParam(defaultValue = "UNREAD") ReadFilter status,
                                                                             @RequestParam(required = false) NotificationKind kind, PageQuery page) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(SliceResponse.of(list.handle(userId, status == ReadFilter.UNREAD, kind, page),
                NotificationResponse::of));
    }

    public enum ReadFilter { UNREAD, ALL }

    @GetMapping("/v1/notifications/unread-count")
    public ResponseEntity<UnreadCount> unreadCount(@CurrentUser UUID userId) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(new UnreadCount(unread.handle(userId)));
    }

    @PostMapping("/v1/notifications/{id}/read")
    public ResponseEntity<Void> read(@CurrentUser UUID userId, @PathVariable UUID id) {
        markRead.handle(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/notifications/read-all")
    public ResponseEntity<Void> readAll(@CurrentUser UUID userId) {
        markAll.handle(userId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ lembretes

    @GetMapping("/v1/reminders")
    public SliceResponse<ReminderResponse> reminders(@CurrentUser UUID userId, @RequestParam(defaultValue = "false") boolean includeDone,
                                                     PageQuery page) {
        return SliceResponse.of(listReminders.handle(userId, includeDone, page), ReminderResponse::of);
    }

    @PostMapping("/v1/reminders")
    public ResponseEntity<ReminderResponse> create(@CurrentUser UUID userId, @Valid @RequestBody ReminderInput body) {
        ReminderResponse created = ReminderResponse.of(createReminder.handle(command(userId, body)));
        return ResponseEntity.created(URI.create("/v1/reminders/" + created.id())).body(created);
    }

    @PutMapping("/v1/reminders/{id}")
    public ReminderResponse update(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody ReminderInput body) {
        return ReminderResponse.of(updateReminder.handle(id, command(userId, body)));
    }

    @PostMapping("/v1/reminders/{id}/done")
    public ReminderResponse done(@CurrentUser UUID userId, @PathVariable UUID id) {
        return ReminderResponse.of(completeReminder.handle(userId, id));
    }

    @PostMapping("/v1/reminders/{id}/snooze")
    public ReminderResponse snooze(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody(required = false) SnoozeInput body) {
        int days = body == null || body.days() == null ? 1 : body.days();
        return ReminderResponse.of(snoozeReminder.handle(userId, id, time.today().plusDays(days)));
    }

    private static ReminderCommand command(UUID userId, ReminderInput body) {
        return new ReminderCommand(userId, body.title(), body.remindOn(), body.time(), body.note(), body.link() == null ? null : body.link().type(),
                body.link() == null ? null : body.link().id());
    }

    // ------------------------------------------------------------------ configurações

    @GetMapping("/v1/settings/notifications")
    public SettingsDto settings(@CurrentUser UUID userId) {
        return SettingsDto.of(getSettings.handle(userId));
    }

    @PutMapping("/v1/settings/notifications")
    public SettingsDto saveSettings(@CurrentUser UUID userId, @Valid @RequestBody SettingsDto body) {
        return SettingsDto.of(saveSettings.handle(new SaveSettingsCommand(userId, body.dailySummary().enabled(),
                LocalTime.parse(body.dailySummary().time()), body.dailySummary().frequency(), body.checkedKinds(), body.quiet(), body.push().enabled(),
                body.displayName(), body.version())));
    }

    // ------------------------------------------------------------------ push (preparado, desligado por padrão)

    @GetMapping("/v1/push/config")
    public PushConfigResponse pushConfig() {
        PushStatus status = pushStatus.handle();
        return new PushConfigResponse(status.enabled(), status.publicKey());
    }

    @PostMapping("/v1/push/subscriptions")
    public ResponseEntity<PushSubscriptionResponse> subscribe(@CurrentUser UUID userId, @Valid @RequestBody SubscriptionInput body) {
        PushSubscriptionResponse created = PushSubscriptionResponse.of(subscribe.handle(new SubscribeCommand(userId, body.endpoint(), body.keys().p256dh(),
                body.keys().auth(), body.userAgent())));
        return ResponseEntity.created(URI.create("/v1/push/subscriptions/" + created.id())).body(created);
    }

    @DeleteMapping("/v1/push/subscriptions/{id}")
    public ResponseEntity<Void> unsubscribe(@CurrentUser UUID userId, @PathVariable UUID id) {
        unsubscribe.handle(userId, id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ mensagem diária

    @GetMapping("/v1/daily-message")
    public ResponseEntity<DailyMessageResponse> dailyMessage(@CurrentUser UUID userId) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(DailyMessageResponse.of(dailyMessage.handle(userId)));
    }
}
