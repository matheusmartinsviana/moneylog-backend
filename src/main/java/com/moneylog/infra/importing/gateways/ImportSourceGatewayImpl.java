package com.moneylog.infra.importing.gateways;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.importing.app.gateways.ImportSourceGateway;

/** Posse de conta e cartão por consulta direta (id de outro usuário não existe). */
public class ImportSourceGatewayImpl implements ImportSourceGateway {

    private final JdbcTemplate jdbc;

    public ImportSourceGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean accountExists(UUID userId, UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM accounts WHERE user_id = ? AND id = ?)", Boolean.class,
                userId, accountId));
    }

    @Override
    public boolean cardExists(UUID userId, UUID cardId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM credit_cards WHERE user_id = ? AND id = ?)", Boolean.class,
                userId, cardId));
    }
}
