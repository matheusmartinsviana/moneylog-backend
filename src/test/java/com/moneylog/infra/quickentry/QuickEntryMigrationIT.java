package com.moneylog.infra.quickentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.moneylog.util.PostgresTestcontainer;

@Tag("i")
@DisplayName("V9.28 e V9.29: tokens e auditoria do lançamento rápido, origem QUICK_SHORTCUT, em banco isolado migrado até a V9.27")
class QuickEntryMigrationIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));
    private static final Timestamp LATER = Timestamp.from(Instant.parse("2026-11-10T12:00:00Z"));
    private static final Date DAY = Date.valueOf(LocalDate.of(2026, 10, 10));

    private static PostgreSQLContainer pg;
    private static DriverManagerDataSource ds;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void subir() {
        pg = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE));
        pg.start();
        ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        jdbc = new JdbcTemplate(ds);
    }

    @AfterAll
    static void descer() {
        pg.stop();
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false)
                .placeholders(Map.of("owner-email", "owner@example.com"));
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static UUID user(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", id, email, NOW, NOW);
        return id;
    }

    private static UUID account(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, user_id, name, type, opening_balance_cents, currency, opening_date, status, created_at, updated_at)
                VALUES (?, ?, 'Carteira', 'WALLET', 0, 'BRL', ?, 'ACTIVE', ?, ?)""", id, user, DAY, NOW, NOW);
        return id;
    }

    private static UUID transaction(UUID user, UUID account, String source) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, payment_method, account_id,
                    source, created_at, updated_at)
                VALUES (?, ?, 'EXPENSE', 'ACTIVE', 1250, 'BRL', ?, 'PIX', ?, ?, ?, ?)""", id, user, DAY, account, source, NOW, NOW);
        return id;
    }

    private static UUID token(UUID user, UUID account, String hash) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO quick_entry_tokens (id, user_id, account_id, label, prefix, token_hash, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, 'Toque duplo', 'abcd1234', ?, ?, ?, ?)""", id, user, account, hash, LATER, NOW, NOW);
        return id;
    }

    private static String hash(char c) {
        return String.valueOf(c).repeat(64);
    }

    @Test
    @DisplayName("banco com dados do dono: a migração passa sem tocar em dado existente, o checksum das antigas segue válido e o schema novo nasce vazio")
    void deveMigrarSemTocarEmDadoExistente() {
        Flyway until927 = flyway("9.27");
        until927.clean();
        assertThat(until927.migrate().success).isTrue();
        UUID owner = user("owner@example.com");
        UUID account = account(owner);
        UUID tx = transaction(owner, account, "HISTORICAL");
        List<Map<String, Object>> before = jdbc.queryForList("select * from transactions where id = ?", tx);

        Flyway latest = flyway(null);
        assertThat(latest.migrate().success).isTrue();
        latest.validate();

        assertThat(jdbc.queryForList("select * from transactions where id = ?", tx)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from quick_entry_tokens", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from quick_entry_audit", Long.class)).isZero();
        assertThat(jdbc.queryForList("select version from flyway_schema_history where version in ('9.28','9.29') and success order by installed_rank",
                String.class)).containsExactly("9.28", "9.29");
    }

    @Test
    @DisplayName("banco do zero: as duas migrações rodam até o fim")
    void deveMigrarBancoDoZero() {
        Flyway latest = flyway(null);
        latest.clean();
        assertThat(latest.migrate().success).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from quick_entry_tokens", Long.class)).isZero();
    }

    @Test
    @DisplayName("dois tokens com o mesmo hash: a unicidade rejeita o segundo, mesmo de outro usuário")
    void deveRejeitarHashRepetido() {
        flyway(null).migrate();
        UUID a = user(UUID.randomUUID() + "@t.test");
        UUID b = user(UUID.randomUUID() + "@t.test");
        token(a, account(a), hash('1'));
        assertThatThrownBy(() -> token(b, account(b), hash('1'))).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_quick_entry_tokens_token_hash");
    }

    @Test
    @DisplayName("conta de outro usuário: a FK composta (account_id, user_id) rejeita")
    void deveRejeitarContaDeOutroUsuario() {
        flyway(null).migrate();
        UUID a = user(UUID.randomUUID() + "@t.test");
        UUID b = user(UUID.randomUUID() + "@t.test");
        UUID contaDeB = account(b);
        assertThatThrownBy(() -> token(a, contaDeB, hash('2'))).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_quick_entry_tokens_account_id_user");
    }

    @Test
    @DisplayName("auditoria: movimentação ou token de outro usuário são rejeitados pelas FKs compostas; do mesmo usuário passa e a movimentação é única")
    void deveIsolarAuditoriaPorUsuario() {
        flyway(null).migrate();
        UUID a = user(UUID.randomUUID() + "@t.test");
        UUID b = user(UUID.randomUUID() + "@t.test");
        UUID contaA = account(a);
        UUID contaB = account(b);
        UUID tokenA = token(a, contaA, hash('3'));
        UUID tokenB = token(b, contaB, hash('4'));
        UUID txA = transaction(a, contaA, "QUICK_SHORTCUT");
        UUID txB = transaction(b, contaB, "QUICK_SHORTCUT");

        assertThatThrownBy(() -> audit(txB, tokenA, a)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_quick_entry_audit_transaction_id_user");
        assertThatThrownBy(() -> audit(txA, tokenB, a)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_quick_entry_audit_token_id_user");
        audit(txA, tokenA, a);
        assertThatThrownBy(() -> audit(txA, tokenA, a)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("pk_quick_entry_audit");
    }

    @Test
    @DisplayName("CHECK de transactions.source aceita QUICK_SHORTCUT e as demais origens, e rejeita valor desconhecido")
    void deveValidarOrigem() {
        flyway(null).migrate();
        UUID a = user(UUID.randomUUID() + "@t.test");
        UUID conta = account(a);
        for (String source : List.of("MANUAL", "ONGOING", "HISTORICAL", "QUICK_SHORTCUT")) {
            assertThat(transaction(a, conta, source)).isNotNull();
        }
        assertThatThrownBy(() -> transaction(a, conta, "SHORTCUT")).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_transactions_source");
    }

    @Test
    @DisplayName("revogação exige data e motivo juntos; validade posterior à criação; nome não vazio")
    void deveValidarEstadoDoToken() {
        flyway(null).migrate();
        UUID a = user(UUID.randomUUID() + "@t.test");
        UUID conta = account(a);
        UUID id = token(a, conta, hash('5'));
        assertThatThrownBy(() -> jdbc.update("update quick_entry_tokens set revoked_at = ? where id = ?", NOW, id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_quick_entry_tokens_revoked");
        assertThatThrownBy(() -> jdbc.update("update quick_entry_tokens set revoked_at = ?, revoked_reason = 'OTHER' where id = ?", NOW, id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_quick_entry_tokens_revoked_reason");
        assertThat(jdbc.update("update quick_entry_tokens set revoked_at = ?, revoked_reason = 'USER_DISABLED' where id = ?", NOW, id)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update quick_entry_tokens set expires_at = created_at where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_quick_entry_tokens_expiry");
        assertThatThrownBy(() -> jdbc.update("update quick_entry_tokens set label = '  ' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_quick_entry_tokens_label");
    }

    private static void audit(UUID transaction, UUID token, UUID user) {
        jdbc.update("insert into quick_entry_audit (transaction_id, token_id, user_id, ip, created_at) values (?, ?, ?, '203.0.113.0', ?)",
                transaction, token, user, NOW);
    }
}
