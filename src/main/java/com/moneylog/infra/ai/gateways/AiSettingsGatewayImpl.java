package com.moneylog.infra.ai.gateways;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.ai.app.gateways.AiSettingsGateway;
import com.moneylog.core.ai.domain.AiSettings;

/** Preferências de IA: uma linha por usuário. */
public class AiSettingsGatewayImpl implements AiSettingsGateway {

    private final JdbcTemplate jdbc;

    public AiSettingsGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AiSettings> find(UUID userId) {
        return jdbc.query("SELECT daily_message_enabled FROM ai_settings WHERE user_id = ?",
                (rs, i) -> new AiSettings(userId, rs.getBoolean("daily_message_enabled")), userId).stream().findFirst();
    }

    @Override
    public AiSettings save(AiSettings settings) {
        jdbc.update("""
                INSERT INTO ai_settings (user_id, daily_message_enabled, updated_at) VALUES (?, ?, now())
                ON CONFLICT (user_id) DO UPDATE SET daily_message_enabled = EXCLUDED.daily_message_enabled, updated_at = now()""",
                settings.userId(), settings.dailyMessageEnabled());
        return settings;
    }
}
