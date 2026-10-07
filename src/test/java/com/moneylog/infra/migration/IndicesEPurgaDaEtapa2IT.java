package com.moneylog.infra.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.auth.app.usecase.PurgeExpiredRefreshTokensUseCase;
import com.moneylog.core.auth.app.usecase.PurgeExpiredRefreshTokensUseCase.PurgeCommand;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;

/** B5 (índices da V2.05) e B7 (coluna do início da família da V2.06 e limpeza dos expirados). */
@IntegrationTest
@DisplayName("Índices da V2.05 e refresh tokens da V2.06 (B5, B7)")
class IndicesEPurgaDaEtapa2IT {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PurgeExpiredRefreshTokensUseCase purge;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager tm;

    private String indexDef(String name) {
        return jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?", String.class, name);
    }

    @Test
    @DisplayName("B5: índices (user_id, transaction_id) nos lançamentos e (user_id, refund_of_id) parcial nas movimentações")
    void deveTerOsIndicesDasConsultasNovas() {
        assertThat(indexDef("ix_ledger_postings_user_transaction")).contains("ledger_postings").contains("(user_id, transaction_id)");
        assertThat(indexDef("ix_transactions_user_refund_of")).contains("(user_id, refund_of_id)").contains("WHERE (refund_of_id IS NOT NULL)");
    }

    private void insertToken(UUID userId, UUID familyId, Instant created, Instant expires) {
        new TransactionTemplate(tm).executeWithoutResult(status -> jdbc.update("""
                insert into refresh_tokens (id, user_id, family_id, token_hash, created_at, updated_at, expires_at, family_started_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), userId, familyId, UUID.randomUUID().toString().replace("-", "") + "0".repeat(32),
                java.sql.Timestamp.from(created), java.sql.Timestamp.from(created), java.sql.Timestamp.from(expires), java.sql.Timestamp.from(created)));
    }

    @Test
    @DisplayName("B7: a limpeza apaga o refresh expirado há mais de 7 dias e preserva o recém-expirado e o ativo")
    void deveLimparSoOsExpiradosHaMuitoTempo() {
        UUID owner = auth.ownerId();
        UUID family = UUID.randomUUID();
        Instant now = Instant.now();
        insertToken(owner, family, now.minus(Duration.ofDays(60)), now.minus(Duration.ofDays(30)));
        insertToken(owner, family, now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(8)));
        insertToken(owner, family, now.minus(Duration.ofDays(33)), now.minus(Duration.ofDays(3)));
        insertToken(owner, family, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(29)));

        int deleted = purge.handle(new PurgeCommand(Duration.ofDays(7)));

        assertThat(deleted).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where family_id = ?", Integer.class, family)).isEqualTo(2);
    }
}
