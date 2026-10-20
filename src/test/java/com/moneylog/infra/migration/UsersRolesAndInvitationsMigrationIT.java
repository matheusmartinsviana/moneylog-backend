package com.moneylog.infra.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
@DisplayName("V9.27 papéis, status, e-mail único sem caixa e convites, em banco isolado migrado até a V9.26")
class UsersRolesAndInvitationsMigrationIT {

    private static final Timestamp OLD = Timestamp.from(Instant.parse("2026-01-01T12:00:00Z"));
    private static final Timestamp NEW = Timestamp.from(Instant.parse("2026-06-01T12:00:00Z"));
    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));

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

    private static Flyway flyway(String ownerEmail, String target) {
        var config = Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false)
                .placeholders(Map.of("owner-email", ownerEmail));
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static Flyway until926(String ownerEmail) {
        Flyway flyway = flyway(ownerEmail, "9.26");
        flyway.clean();
        assertThat(flyway.migrate().success).isTrue();
        return flyway;
    }

    private static UUID user(String email, Timestamp createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", id, email, createdAt, createdAt);
        return id;
    }

    private static UUID userWithId(UUID id, String email, Timestamp createdAt) {
        jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", id, email, createdAt, createdAt);
        return id;
    }

    private static void subscription(UUID user) {
        jdbc.update("insert into push_subscriptions (id,user_id,endpoint,p256dh,auth,created_at) values (?,?,?,'p','a',?)",
                UUID.randomUUID(), user, "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID(), NOW);
    }

    private static String role(UUID id) {
        return jdbc.queryForObject("select role from users where id = ?", String.class, id);
    }

    private static List<String> snapshotWithoutUsers() {
        List<String> out = new ArrayList<>();
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname = 'public' and tablename not in ('users', 'flyway_schema_history', 'user_invitations') order by tablename", String.class)) {
            out.add(table + "=" + jdbc.queryForObject("select count(*) || ':' || coalesce(md5(string_agg(t::text, ',' order by t::text)), '') from " + table + " t", String.class));
        }
        return out;
    }

    private static void migrateAll(String ownerEmail) {
        Flyway all = flyway(ownerEmail, null);
        assertThat(all.migrate().success).isTrue();
        assertThat(all.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @DisplayName("um usuário com dados: vira OWNER/ACTIVE, nenhuma outra tabela muda e o checksum das migrações anteriores segue válido")
    void donoUnicoComDados() {
        until926("dono@example.com");
        UUID dono = user("dono@example.com", OLD);
        subscription(dono);
        subscription(dono);
        List<String> before = snapshotWithoutUsers();

        migrateAll("dono@example.com");

        assertThat(role(dono)).isEqualTo("OWNER");
        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, dono)).isEqualTo("ACTIVE");
        assertThat(snapshotWithoutUsers()).isEqualTo(before);
    }

    @Test
    @DisplayName("dois usuários e MONEYLOG_OWNER_EMAIL é o do mais novo (com caixa diferente): esse vira OWNER e o outro MEMBER")
    void ambienteEscolheOMaisNovo() {
        until926("Novo@Example.com");
        UUID antigo = user("antigo@example.com", OLD);
        UUID novo = user("novo@example.com", NEW);

        migrateAll("Novo@Example.com");

        assertThat(role(novo)).isEqualTo("OWNER");
        assertThat(role(antigo)).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("dois usuários e variável vazia: o de menor created_at vira OWNER")
    void variavelVaziaUsaOMaisAntigo() {
        until926("");
        UUID antigo = user("antigo@example.com", OLD);
        UUID novo = user("novo@example.com", NEW);

        migrateAll("");

        assertThat(role(antigo)).isEqualTo("OWNER");
        assertThat(role(novo)).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("dois usuários e variável sem correspondência: o mais antigo vira OWNER")
    void semCorrespondenciaUsaOMaisAntigo() {
        until926("ninguem@example.com");
        UUID novo = user("novo@example.com", NEW);
        UUID antigo = user("antigo@example.com", OLD);

        migrateAll("ninguem@example.com");

        assertThat(role(antigo)).isEqualTo("OWNER");
        assertThat(role(novo)).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("mesmo created_at: o desempate é pelo menor id, de forma determinística")
    void desempatePorId() {
        until926("");
        UUID menor = userWithId(new UUID(0, 1), "a@example.com", OLD);
        UUID maior = userWithId(new UUID(0, 2), "b@example.com", OLD);

        migrateAll("");

        assertThat(role(menor)).isEqualTo("OWNER");
        assertThat(role(maior)).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("banco vazio: a migração não cria nem altera ninguém e depois o primeiro OWNER é aceito")
    void bancoVazio() {
        until926("dono@example.com");

        migrateAll("dono@example.com");

        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
        jdbc.update("insert into users (id,email,role,created_at,updated_at) values (?,?,'OWNER',?,?)", UUID.randomUUID(), "dono@example.com", NOW, NOW);
        assertThat(jdbc.queryForObject("select status from users", String.class)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("do zero: toda a cadeia migra e valida")
    void doZero() {
        Flyway all = flyway("", null);
        all.clean();

        assertThat(all.migrate().success).isTrue();
        assertThat(all.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @DisplayName("e-mails que só diferem pela caixa já existentes abortam a migração com mensagem clara")
    void colisaoAbortaAMigracao() {
        until926("");
        user("Duplo@Example.com", OLD);
        user("duplo@example.com", NEW);

        assertThatThrownBy(() -> flyway("", null).migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("e-mails iguais sem distincao de caixa");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'users' and column_name = 'role'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("depois da V9.27 o banco recusa segundo OWNER, e-mail que difere só pela caixa, papel e status fora do domínio")
    void restricoesDeUsuarios() {
        until926("dono@example.com");
        user("dono@example.com", OLD);
        migrateAll("dono@example.com");

        assertThatThrownBy(() -> jdbc.update("insert into users (id,email,role,created_at,updated_at) values (?,?,'OWNER',?,?)",
                UUID.randomUUID(), "outro-dono@example.com", NOW, NOW)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_users_single_owner");
        assertThatThrownBy(() -> user("DONO@EXAMPLE.COM", NOW)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_users_email_lower");
        assertThatThrownBy(() -> jdbc.update("insert into users (id,email,role,created_at,updated_at) values (?,?,'ADMIN',?,?)",
                UUID.randomUUID(), "x@example.com", NOW, NOW)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_role");
        assertThatThrownBy(() -> jdbc.update("insert into users (id,email,status,created_at,updated_at) values (?,?,'BANIDO',?,?)",
                UUID.randomUUID(), "y@example.com", NOW, NOW)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_status");
        UUID membro = user("membro@example.com", NOW);
        assertThat(role(membro)).isEqualTo("MEMBER");
        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, membro)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("convites: dois PENDING para o mesmo e-mail (caixa diferente) são recusados; ACCEPTED/REVOKED/EXPIRED convivem; token_hash é único")
    void restricoesDeConvites() {
        until926("dono@example.com");
        UUID dono = user("dono@example.com", OLD);
        UUID convidado = user("convidado@example.com", NEW);
        migrateAll("dono@example.com");
        Timestamp later = Timestamp.from(Instant.parse("2026-10-17T12:00:00Z"));

        insertInvitation("Convidado@Example.com", "a".repeat(64), "PENDING", dono, NOW, later, null, null);

        assertThatThrownBy(() -> insertInvitation("convidado@example.com", "b".repeat(64), "PENDING", dono, NOW, later, null, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_user_invitations_pending_email");
        insertInvitation("convidado@example.com", "c".repeat(64), "REVOKED", dono, NOW, later, null, null);
        insertInvitation("convidado@example.com", "d".repeat(64), "EXPIRED", dono, NOW, later, null, null);
        insertInvitation("convidado@example.com", "e".repeat(64), "ACCEPTED", dono, NOW, later, NOW, convidado);
        assertThatThrownBy(() -> insertInvitation("outro@example.com", "a".repeat(64), "REVOKED", dono, NOW, later, null, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_user_invitations_token_hash");
        assertThatThrownBy(() -> insertInvitation("z@example.com", "f".repeat(64), "ACCEPTED", dono, NOW, later, null, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_user_invitations_accepted");
        assertThatThrownBy(() -> insertInvitation("z@example.com", "9".repeat(64), "CANCELADO", dono, NOW, later, null, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_user_invitations_status");
        assertThatThrownBy(() -> insertInvitation("z@example.com", "8".repeat(64), "PENDING", dono, later, NOW, null, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_user_invitations_expiry");
        assertThatThrownBy(() -> insertInvitation("z@example.com", "7".repeat(64), "PENDING", UUID.randomUUID(), NOW, later, null, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_user_invitations_invited_by");
        assertThatThrownBy(() -> jdbc.update("update user_invitations set delivery_status = 'LOST' where token_hash = ?", "a".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_user_invitations_delivery");
    }

    @Test
    @DisplayName("e-mail do dono com aspa simples escapado migra sem quebrar o SQL e elege o dono; status DISABLED exige disabled_at")
    void aspaSimplesNoPlaceholderEStatusDesativado() {
        until926("o'brien@example.com");
        UUID velho = user("velho@example.com", OLD);
        UUID obrien = user("O'Brien@example.com", NEW);

        migrateAll(com.moneylog.infra.config.FlywayPlaceholderConfig.escapeSqlLiteral("o'brien@example.com"));

        assertThat(role(obrien)).isEqualTo("OWNER");
        assertThat(role(velho)).isEqualTo("MEMBER");
        assertThatThrownBy(() -> jdbc.update("update users set status = 'DISABLED' where id = ?", velho))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_users_disabled");
        assertThatThrownBy(() -> jdbc.update("update users set disabled_at = ? where id = ?", NOW, velho))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_users_disabled");
        assertThat(jdbc.update("update users set status = 'DISABLED', disabled_at = ? where id = ?", NOW, velho)).isEqualTo(1);
    }

    private static void insertInvitation(String email, String tokenHash, String status, UUID invitedBy, Timestamp createdAt,
                                         Timestamp expiresAt, Timestamp acceptedAt, UUID acceptedUser) {
        jdbc.update("""
                insert into user_invitations (id,email,token_hash,status,invited_by,created_at,updated_at,expires_at,last_sent_at,delivery_status,accepted_at,accepted_user_id)
                values (?,?,?,?,?,?,?,?,?,'SENT',?,?)
                """, UUID.randomUUID(), email, tokenHash, status, invitedBy, createdAt, createdAt, expiresAt, createdAt, acceptedAt, acceptedUser);
    }
}
