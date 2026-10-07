package com.moneylog.infra.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.moneylog.core.backup.app.BackupCrypto;
import com.moneylog.core.backup.app.BackupException;
import com.moneylog.core.backup.app.BackupService;
import com.moneylog.core.backup.app.DatabaseDumper;
import com.moneylog.util.PostgresTestcontainer;

/**
 * T176 CA1/CA4: backup de uma base migrada e com dados, restauração numa base VAZIA e comparação por consultas (saldos, razão soma zero,
 * metas, notas e um hash de todas as linhas de todas as tabelas). pg_dump/pg_restore rodam dentro dos containers (mesma imagem do compose).
 */
@Tag("i")
@DisplayName("Backup e restauração do banco (Testcontainers)")
class BackupRestoreIT {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    /** pg_dump no container de origem; pg_restore no container de destino. */
    private record ContainerDumper(PostgreSQLContainer source, PostgreSQLContainer target) implements DatabaseDumper {
        @Override
        public void dump(Path file) {
            try {
                var r = source.execInContainer("pg_dump", "-U", source.getUsername(), "--format=custom", "--no-owner", "--no-privileges",
                        "--file", "/tmp/m.dump", source.getDatabaseName());
                if (r.getExitCode() != 0) {
                    throw new BackupException("pg_dump falhou");
                }
                source.copyFileFromContainer("/tmp/m.dump", file.toString());
            } catch (Exception e) {
                throw new BackupException("dump de teste falhou", e);
            }
        }

        @Override
        public void restore(Path file) {
            try {
                target.copyFileToContainer(MountableFile.forHostPath(file), "/tmp/m.dump");
                var r = target.execInContainer("pg_restore", "-U", target.getUsername(), "--no-owner", "--no-privileges", "--exit-on-error",
                        "--dbname", target.getDatabaseName(), "/tmp/m.dump");
                if (r.getExitCode() != 0) {
                    throw new BackupException("pg_restore falhou: " + r.getStderr());
                }
            } catch (BackupException e) {
                throw e;
            } catch (Exception e) {
                throw new BackupException("restore de teste falhou", e);
            }
        }
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer c) {
        DataSource ds = new DriverManagerDataSource(c.getJdbcUrl(), c.getUsername(), c.getPassword());
        return new JdbcTemplate(ds);
    }

    private static void seed(JdbcTemplate j) {
        UUID user = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        j.update("INSERT INTO users (id, email, created_at, updated_at) VALUES (?, 'dono@moneylog.test', now(), now())", user);
        j.update("""
                INSERT INTO accounts (id, user_id, name, type, opening_balance_cents, currency, opening_date, status, created_at, updated_at, version)
                VALUES (?, ?, 'Conta Teste', 'CHECKING', 123456, 'BRL', DATE '2026-10-01', 'ACTIVE', now(), now(), 0)""", account, user);
        for (Object[] p : new Object[][] {{"ACCOUNT", account, 123456L}, {"EQUITY", null, -123456L}}) {
            j.update("""
                    INSERT INTO ledger_postings (id, user_id, source_type, source_id, book_type, book_id, competence, effective_date,
                        amount_cents, currency, active, created_at, updated_at)
                    VALUES (?, ?, 'ACCOUNT_OPENING', ?, ?, ?, '2026-10', DATE '2026-10-01', ?, 'BRL', TRUE, now(), now())""",
                    UUID.randomUUID(), user, account, p[0], p[1], p[2]);
        }
        j.update("""
                INSERT INTO goals (id, user_id, name, target_cents, currency, priority, created_at, updated_at, version)
                VALUES (?, ?, 'Reserva', 500000, 'BRL', 'HIGH', now(), now(), 0)""", UUID.randomUUID(), user);
        j.update("""
                INSERT INTO notes (id, user_id, path, path_key, title, body, created_at, updated_at, version)
                VALUES (?, ?, 'Casa/Reforma.md', 'casa/reforma.md', 'Reforma', 'Orçamento da reforma: acentuação ok', now(), now(), 0)""",
                UUID.randomUUID(), user);
    }

    /** Resumo comparável: saldo por conta, soma do razão, e um hash por tabela (todas as linhas, ordenadas). */
    private static List<String> snapshot(JdbcTemplate j) {
        List<String> out = new ArrayList<>();
        out.add("saldos=" + j.queryForList("""
                SELECT a.name || ':' || COALESCE(SUM(p.amount_cents), 0) FROM accounts a
                LEFT JOIN ledger_postings p ON p.book_type = 'ACCOUNT' AND p.book_id = a.id AND p.active
                GROUP BY a.id, a.name ORDER BY a.name""", String.class));
        out.add("razao=" + j.queryForObject("SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_postings WHERE active", Long.class));
        for (String table : j.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename", String.class)) {
            out.add(table + "=" + j.queryForObject("SELECT count(*) || ':' || COALESCE(md5(string_agg(t::text, ',' ORDER BY t::text)), '') FROM "
                    + table + " t", String.class));
        }
        return out;
    }

    @Test
    @DisplayName("backup cifrado + restauração numa base vazia: tudo idêntico; chave errada falha sem tocar na base vazia")
    void deveRestaurarIdentico() {
        try (PostgreSQLContainer source = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE));
             PostgreSQLContainer target = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE))) {
            source.start();
            target.start();
            Flyway.configure().dataSource(source.getJdbcUrl(), source.getUsername(), source.getPassword()).locations("classpath:db/migration")
                    .load().migrate();
            JdbcTemplate src = jdbc(source);
            seed(src);
            ContainerDumper dumper = new ContainerDumper(source, target);
            var storage = new LocalDirBackupStorage(dir);
            BackupService service = new BackupService(dumper, storage, Optional.of(new BackupCrypto("chave-de-teste-nao-e-segredo-123")),
                    "moneylog/", 7, CLOCK);

            String key = service.backup();

            BackupService wrongKey = new BackupService(dumper, storage, Optional.of(new BackupCrypto("outra-chave-bem-diferente-999")),
                    "moneylog/", 7, CLOCK);
            assertThatThrownBy(() -> wrongKey.restore(key)).isInstanceOf(BackupException.class);
            JdbcTemplate dst = jdbc(target);
            assertThat(dst.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'", Long.class)).isZero();

            service.restore(key);

            List<String> before = snapshot(src);
            assertThat(before).contains("saldos=[Conta Teste:123456]", "razao=0");
            assertThat(snapshot(dst)).isEqualTo(before);
        }
    }
}
