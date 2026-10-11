package com.moneylog.infra.notification.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.DailyMessageView;
import com.moneylog.core.notification.domain.Notification;
import com.moneylog.core.notification.domain.NotificationKind;
import com.moneylog.core.notification.domain.NotificationSettings;
import com.moneylog.core.notification.domain.QuietHours;
import com.moneylog.core.notification.domain.Reminder;
import com.moneylog.core.notification.domain.SummaryFrequency;
import com.moneylog.core.notification.exception.NotificationExceptions.InvalidNotificationSettingsException;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class NotificationDtos {

    static final String TIME = "^([01]\\d|2[0-3]):[0-5]\\d$";

    private NotificationDtos() {
    }

    public record Ref(String type, UUID id) {
    }

    public record NotificationResponse(UUID id, NotificationKind kind, String titleKey, String bodyKey, Map<String, String> params, Ref ref,
                                       LocalDate onDate, LocalDate dueOn, Instant readAt, Instant createdAt) {
        static NotificationResponse of(Notification n) {
            return new NotificationResponse(n.id(), n.kind(), n.titleKey(), n.titleKey() + ".body", n.params(),
                    new Ref(n.draft().refType(), n.draft().refId()), n.onDate(), n.draft().dueOn(), n.readAt(), n.createdAt());
        }
    }

    public record UnreadCount(long count) {
    }

    public record LinkDto(@NotBlank @Pattern(regexp = "^[A-Z_]{1,20}$") String type, @NotNull UUID id) {
    }

    public record ReminderResponse(UUID id, String title, LocalDate remindOn, String remindAt, String note, LinkDto link, boolean done, Instant doneAt,
                                   Instant createdAt) {
        static ReminderResponse of(Reminder r) {
            return new ReminderResponse(r.id(), r.title(), r.remindOn(), r.remindAt() == null ? null : r.remindAt().toString(), r.note(),
                    r.linkType() == null ? null : new LinkDto(r.linkType(), r.linkId()), r.isDone(), r.doneAt(), r.createdAt());
        }
    }

    public record PushSubscriptionResponse(UUID id, String endpoint, Instant createdAt) {
        static PushSubscriptionResponse of(PushSubscription s) {
            return new PushSubscriptionResponse(s.id(), s.endpoint(), s.createdAt());
        }
    }

    public record PushConfigResponse(boolean enabled, String publicKey) {
    }

    public record DailyMessageResponse(String key, Map<String, String> params, String source, Instant generatedAt) {
        static DailyMessageResponse of(DailyMessageView v) {
            return new DailyMessageResponse(v.message().key(), v.message().params(), v.source(), v.generatedAt());
        }
    }

    public record ReminderInput(@NotBlank @Size(max = 120) @NoControlChars String title, @NotNull LocalDate remindOn,
                                @Pattern(regexp = TIME) String remindAt, @Size(max = 500) @NoControlChars(allowLineBreaks = true) String note,
                                @Valid LinkDto link) {
        LocalTime time() {
            return remindAt == null ? null : LocalTime.parse(remindAt);
        }
    }

    public record SnoozeInput(@Min(1) @Max(365) Integer days) {
    }

    public record SubscriptionKeys(@NotBlank @Size(max = 200) String p256dh, @NotBlank @Size(max = 100) String auth) {
    }

    public record SubscriptionInput(@NotBlank @Size(max = 500) String endpoint, @NotNull @Valid SubscriptionKeys keys,
                                    @Size(max = 200) String userAgent) {
    }

    public record DailySummaryDto(boolean enabled, @NotNull @Pattern(regexp = TIME) String time, @NotNull SummaryFrequency frequency) {
    }

    public record QuietHoursDto(@NotNull @Pattern(regexp = TIME) String from, @NotNull @Pattern(regexp = TIME) String to) {
    }

    public record PushDto(boolean enabled) {
    }

    public record SettingsDto(@NotNull @Valid DailySummaryDto dailySummary, Map<NotificationKind, Boolean> kinds, @NotNull @Valid QuietHoursDto quietHours,
                              @NotNull @Valid PushDto push, @Size(max = 60) @NoControlChars String displayName, @NotNull Long version) {

        static SettingsDto of(NotificationSettings s) {
            Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationKind.class);
            for (NotificationKind kind : NotificationKind.values()) {
                if (kind.configurable()) {
                    kinds.put(kind, s.isEnabled(kind));
                }
            }
            return new SettingsDto(new DailySummaryDto(s.summaryEnabled(), s.summaryTime().toString(), s.summaryFrequency()), kinds,
                    new QuietHoursDto(s.quietHours().from().toString(), s.quietHours().to().toString()), new PushDto(s.pushEnabled()),
                    s.displayName(), s.version());
        }

        QuietHours quiet() {
            return new QuietHours(LocalTime.parse(quietHours.from()), LocalTime.parse(quietHours.to()));
        }

        Map<NotificationKind, Boolean> checkedKinds() {
            if (kinds == null) {
                return Map.of();
            }
            for (NotificationKind kind : kinds.keySet()) {
                if (!kind.configurable()) {
                    throw new InvalidNotificationSettingsException("o tipo " + kind + " não é configurável.");
                }
            }
            return kinds;
        }
    }
}
