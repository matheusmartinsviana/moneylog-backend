package com.moneylog.infra.notification.gateways;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.notification.app.gateways.NotificationSettingsGateway;
import com.moneylog.core.notification.domain.NotificationAlertSettings;
import com.moneylog.core.notification.domain.NotificationKind;
import com.moneylog.core.notification.domain.NotificationSettings;
import com.moneylog.core.notification.domain.QuietHours;
import com.moneylog.core.notification.domain.SummaryFrequency;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public class NotificationSettingsGatewayImpl implements NotificationSettingsGateway {

    private final JdbcTemplate jdbc;

    public NotificationSettingsGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<NotificationSettings> find(UUID userId) {
        return jdbc.query("SELECT summary_enabled, summary_time, summary_frequency, disabled_kinds, quiet_from, quiet_to, push_enabled, display_name, "
                        + "subscription_unused_days, invoice_due_lead_days, document_divergence_tolerance_cents, version FROM notification_settings WHERE user_id = ?", (rs, i) -> {
            Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationKind.class);
            Arrays.stream(rs.getString("disabled_kinds").split(",")).filter(s -> !s.isBlank())
                    .forEach(s -> kinds.put(NotificationKind.valueOf(s), false));
            return NotificationSettings.restore(userId, rs.getBoolean("summary_enabled"), rs.getObject("summary_time", LocalTime.class),
                    SummaryFrequency.valueOf(rs.getString("summary_frequency")), kinds,
                    new QuietHours(rs.getObject("quiet_from", LocalTime.class), rs.getObject("quiet_to", LocalTime.class)), rs.getBoolean("push_enabled"),
                    rs.getString("display_name"),
                    new NotificationAlertSettings(rs.getObject("subscription_unused_days", Integer.class), rs.getInt("invoice_due_lead_days"),
                            Money.ofCents(rs.getLong("document_divergence_tolerance_cents"), CurrencyCode.BRL)), rs.getLong("version"));
        }, userId).stream().findFirst();
    }

    @Override
    public Optional<NotificationSettings> save(NotificationSettings s, long expectedVersion) {
        String disabled = s.kinds().entrySet().stream().filter(e -> !e.getValue()).map(e -> e.getKey().name()).sorted()
                .collect(Collectors.joining(","));
        int changed;
        if (expectedVersion == 0) {
            changed = jdbc.update("""
                    INSERT INTO notification_settings (user_id, summary_enabled, summary_time, summary_frequency, disabled_kinds, quiet_from, quiet_to,
                        push_enabled, display_name, subscription_unused_days, invoice_due_lead_days, document_divergence_tolerance_cents, version,
                        updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, now())
                    ON CONFLICT (user_id) DO NOTHING""", s.userId(), s.summaryEnabled(), time(s.summaryTime()), s.summaryFrequency().name(), disabled,
                    time(s.quietHours().from()), time(s.quietHours().to()), s.pushEnabled(), s.displayName(), s.alerts().subscriptionUnusedDays(),
                    s.alerts().invoiceDueLeadDays(), s.alerts().documentDivergenceTolerance().cents());
        } else {
            changed = jdbc.update("""
                    UPDATE notification_settings SET summary_enabled = ?, summary_time = ?, summary_frequency = ?, disabled_kinds = ?, quiet_from = ?,
                        quiet_to = ?, push_enabled = ?, display_name = ?, subscription_unused_days = ?, invoice_due_lead_days = ?,
                        document_divergence_tolerance_cents = ?, version = version + 1, updated_at = now()
                    WHERE user_id = ? AND version = ?""", s.summaryEnabled(), time(s.summaryTime()), s.summaryFrequency().name(), disabled,
                    time(s.quietHours().from()), time(s.quietHours().to()), s.pushEnabled(), s.displayName(), s.alerts().subscriptionUnusedDays(),
                    s.alerts().invoiceDueLeadDays(), s.alerts().documentDivergenceTolerance().cents(), s.userId(), expectedVersion);
        }
        return changed == 0 ? Optional.empty() : find(s.userId());
    }

    @Override
    public boolean setPushEnabled(UUID userId, boolean enabled) {
        if (enabled) {
            return jdbc.update("""
                    INSERT INTO notification_settings (user_id, push_enabled, version, updated_at)
                    VALUES (?, TRUE, 1, now())
                    ON CONFLICT (user_id) DO UPDATE SET push_enabled = TRUE, version = notification_settings.version + 1, updated_at = now()
                    WHERE notification_settings.push_enabled = FALSE""", userId) > 0;
        }
        return jdbc.update("""
                UPDATE notification_settings SET push_enabled = FALSE, version = version + 1, updated_at = now()
                WHERE user_id = ? AND push_enabled = TRUE""", userId) > 0;
    }

    private static LocalTime time(LocalTime time) {
        return time;
    }
}
