package com.moneylog.infra.quickentry.gateways;

import java.time.ZoneId;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.quickentry.app.gateways.UserTimeZoneGateway;

public class UserTimeZoneGatewayImpl implements UserTimeZoneGateway {

    private final JdbcTemplate jdbc;

    public UserTimeZoneGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ZoneId timeZoneOf(UUID userId) {
        String zone = jdbc.query("SELECT time_zone FROM users WHERE id = ?", (rs, row) -> rs.getString("time_zone"), userId)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("usuário do token não existe"));
        return ZoneId.of(zone.strip());
    }
}
