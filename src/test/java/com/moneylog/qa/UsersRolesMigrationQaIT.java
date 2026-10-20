package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.moneylog.util.PostgresTestcontainer;

@Tag("i")
@DisplayName("QA T2: V9.27 com variável de ambiente suja (espaços, caixa, caracteres especiais) e linhas legadas")
class UsersRolesMigrationQaIT {

    private static final Timestamp OLD = Timestamp.from(Instant.parse("2026-01-01T12:00:00Z"));
    private static final Timestamp NEW = Timestamp.from(Instant.parse("2026-06-01T12:00:00Z"));

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
        var config = Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).placeholders(Map.of("owner-email", ownerEmail));
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static void until926() {
        Flyway flyway = flyway("", "9.26");
        flyway.clean();
        assertThat(flyway.migrate().success).isTrue();
    }

    private static UUID user(String email, Timestamp createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", id, email, createdAt, createdAt);
        return id;
    }

    private static String role(UUID id) {
        return jdbc.queryForObject("select role from users where id = ?", String.class, id);
    }

    @Test
    @DisplayName("variável com espaços nas pontas e caixa alta elege o mais novo; linha legada com caixa mista também casa")
    void variavelSujaCasaPeloLower() {
        until926();
        UUID antigo = user("antigo@example.com", OLD);
        UUID novo = user("Novo.Legado@Example.COM", NEW);

        flyway("   NOVO.LEGADO@example.com  ", null).migrate();

        assertThat(role(novo)).isEqualTo("OWNER");
        assertThat(role(antigo)).isEqualTo("MEMBER");
        assertThat(jdbc.queryForObject("select count(*) from users where role = 'OWNER'", Integer.class)).isEqualTo(1);
    }

    @org.junit.jupiter.api.Disabled("BUG-QA-T2-02: btrim só tira espaços; MONEYLOG_OWNER_EMAIL com tab/CR/LF no fim não casa na V9.27, mas User.normalize (strip) casa no bootstrap")
    @Test
    @DisplayName("variável com quebra de linha/tab no fim (arquivo .env com CRLF) elege o mesmo dono que o OwnerBootstrap")
    void variavelComQuebraDeLinha() {
        until926();
        UUID antigo = user("antigo@example.com", OLD);
        UUID novo = user("novo@example.com", NEW);

        flyway("novo@example.com\r\n", null).migrate();

        assertThat(role(novo)).isEqualTo("OWNER");
        assertThat(role(antigo)).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("variável só com espaços (equivale a vazia) cai no mais antigo e cria exatamente um OWNER")
    void variavelSoEspacos() {
        until926();
        UUID antigo = user("a@example.com", OLD);
        UUID novo = user("b@example.com", NEW);

        flyway("     ", null).migrate();

        assertThat(role(antigo)).isEqualTo("OWNER");
        assertThat(role(novo)).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("variável com %, _, \\ e $ não vira curinga nem quebra o SQL: só casa o e-mail exato")
    void caracteresEspeciaisNaoViramCuringa() {
        until926();
        UUID antigo = user("antigo@example.com", OLD);
        UUID novo = user("novo+tag@example.com", NEW);

        flyway("%@example.com", null).migrate();

        assertThat(role(antigo)).isEqualTo("OWNER");
        assertThat(role(novo)).isEqualTo("MEMBER");
    }
}
