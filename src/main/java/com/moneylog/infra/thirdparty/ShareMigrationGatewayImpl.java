package com.moneylog.infra.thirdparty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.thirdparty.app.gateways.ShareMigrationGateway;
import com.moneylog.core.thirdparty.domain.ShareMigrationReport;

public class ShareMigrationGatewayImpl implements ShareMigrationGateway {

    private static final Logger log = LoggerFactory.getLogger(ShareMigrationGatewayImpl.class);

    private final JdbcTemplate jdbc;
    private final Path reportDir;

    public ShareMigrationGatewayImpl(JdbcTemplate jdbc, Path reportDir) {
        this.jdbc = jdbc;
        this.reportDir = reportDir;
    }

    @Override
    public void archive(MigratedMark m) {
        jdbc.update("INSERT INTO third_party_mark_migrations (id, user_id, transaction_id, person_id, mark_id, mark_note, mark_amount_cents,"
                        + " split_mode, third_cents, refunded_cents, legacy_received_cents, report_hash, migrated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                m.id(), m.mark().userId(), m.mark().transactionId(), m.mark().personId(), m.mark().id(), m.mark().note(),
                m.mark().amount() == null ? null : m.mark().amount().cents(), m.mode().name(), m.third().cents(), m.refunded().cents(),
                m.legacyReceived().cents(), m.reportHash(), Timestamp.from(m.migratedAt()));
    }

    @Override
    public boolean existsApplied(UUID userId, String reportHash) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM third_party_mark_migrations WHERE user_id = ? AND report_hash = ?",
                Integer.class, userId, reportHash);
        return count != null && count > 0;
    }

    @Override
    public void saveReport(UUID userId, ShareMigrationReport report) {
        try {
            Path dir = reportDir.resolve(userId.toString());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("share-migration-" + report.hash() + ".txt"), report.canonical(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Não foi possível gravar o relatório de migração de marcas: {}", e.getClass().getSimpleName());
        }
    }
}
