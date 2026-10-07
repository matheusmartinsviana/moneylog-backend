package com.moneylog.core.notification.domain;

import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.notification.exception.NotificationExceptions.InvalidNotificationSettingsException;
import com.moneylog.core.sk.text.PlainText;

/**
 * Configuração de notificações do usuário (S-175). Padrão: tudo ligado na central, push desligado, resumo diário às 08:00 todo dia, silêncio
 * 22:00 a 07:00. Sem setters públicos: {@link #update} valida e devolve a nova versão lógica.
 */
public final class NotificationSettings {

    public static final LocalTime DEFAULT_SUMMARY_TIME = LocalTime.of(8, 0);
    public static final int DISPLAY_NAME_MAX = 60;

    private final UUID userId;
    private boolean summaryEnabled;
    private LocalTime summaryTime;
    private SummaryFrequency summaryFrequency;
    private final Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationKind.class);
    private QuietHours quietHours;
    private boolean pushEnabled;
    private String displayName;
    private final long version;

    private NotificationSettings(UUID userId, boolean summaryEnabled, LocalTime summaryTime, SummaryFrequency summaryFrequency,
                                 Map<NotificationKind, Boolean> kinds, QuietHours quietHours, boolean pushEnabled, String displayName,
                                 long version) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.summaryEnabled = summaryEnabled;
        this.summaryTime = Objects.requireNonNull(summaryTime, "summaryTime");
        this.summaryFrequency = Objects.requireNonNull(summaryFrequency, "summaryFrequency");
        for (NotificationKind kind : NotificationKind.values()) {
            if (kind.configurable()) {
                this.kinds.put(kind, kinds.getOrDefault(kind, true));
            }
        }
        this.quietHours = Objects.requireNonNull(quietHours, "quietHours");
        this.pushEnabled = pushEnabled;
        this.displayName = cleanName(displayName);
        this.version = version;
    }

    public static NotificationSettings defaults(UUID userId) {
        return new NotificationSettings(userId, true, DEFAULT_SUMMARY_TIME, SummaryFrequency.DAILY, Map.of(), QuietHours.DEFAULT, false, null, 0);
    }

    public static NotificationSettings restore(UUID userId, boolean summaryEnabled, LocalTime summaryTime, SummaryFrequency frequency,
                                               Map<NotificationKind, Boolean> kinds, QuietHours quietHours, boolean pushEnabled, String displayName,
                                               long version) {
        return new NotificationSettings(userId, summaryEnabled, summaryTime, frequency, kinds, quietHours, pushEnabled, displayName, version);
    }

    /** Substitui as preferências (a versão avança na gravação). */
    public void update(boolean newSummaryEnabled, LocalTime newSummaryTime, SummaryFrequency newFrequency, Map<NotificationKind, Boolean> newKinds,
                       QuietHours newQuietHours, boolean newPushEnabled, String newDisplayName) {
        this.summaryEnabled = newSummaryEnabled;
        this.summaryTime = Objects.requireNonNull(newSummaryTime, "summaryTime");
        this.summaryFrequency = Objects.requireNonNull(newFrequency, "frequency");
        for (NotificationKind kind : NotificationKind.values()) {
            if (kind.configurable() && newKinds.containsKey(kind)) {
                this.kinds.put(kind, newKinds.get(kind));
            }
        }
        this.quietHours = Objects.requireNonNull(newQuietHours, "quietHours");
        this.pushEnabled = newPushEnabled;
        this.displayName = cleanName(newDisplayName);
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.strip();
        if (trimmed.length() > DISPLAY_NAME_MAX || !PlainText.isSafeSingleLine(trimmed)) {
            throw new InvalidNotificationSettingsException("o nome de exibição deve ter até " + DISPLAY_NAME_MAX + " caracteres, sem controles.");
        }
        return trimmed;
    }

    /** O resumo diário tem chave própria; os demais tipos, a lista. Lembrete manual e agrupado nunca são desligados. */
    public boolean isEnabled(NotificationKind kind) {
        if (kind == NotificationKind.DAILY_SUMMARY) {
            return summaryEnabled && summaryFrequency != SummaryFrequency.OFF;
        }
        return !kind.configurable() || kinds.getOrDefault(kind, true);
    }

    public UUID userId() {
        return userId;
    }

    public boolean summaryEnabled() {
        return summaryEnabled;
    }

    public LocalTime summaryTime() {
        return summaryTime;
    }

    public SummaryFrequency summaryFrequency() {
        return summaryFrequency;
    }

    public Map<NotificationKind, Boolean> kinds() {
        return Map.copyOf(kinds);
    }

    public QuietHours quietHours() {
        return quietHours;
    }

    public boolean pushEnabled() {
        return pushEnabled;
    }

    /** {@code null} = sem nome (a mensagem diária sai só "Bom dia."). */
    public String displayName() {
        return displayName;
    }

    public long version() {
        return version;
    }
}
