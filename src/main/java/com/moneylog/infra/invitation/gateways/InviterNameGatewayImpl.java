package com.moneylog.infra.invitation.gateways;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.invitation.app.gateways.InviterNameGateway;

public class InviterNameGatewayImpl implements InviterNameGateway {

    private final JdbcTemplate jdbc;

    public InviterNameGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> findDisplayName(UUID userId) {
        return jdbc.query("SELECT display_name FROM notification_settings WHERE user_id = ?",
                (rs, row) -> rs.getString(1), userId).stream().filter(java.util.Objects::nonNull).findFirst();
    }
}
