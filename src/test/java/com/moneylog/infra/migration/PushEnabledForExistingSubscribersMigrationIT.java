package com.moneylog.infra.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.moneylog.util.PostgresTestcontainer;

@Tag("i")
@DisplayName("V9.26 religa o push de quem já assinou, em banco isolado migrado até a V9.25")
class PushEnabledForExistingSubscribersMigrationIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));

    private static UUID user(JdbcTemplate jdbc, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", id, name + "@example.com", NOW, NOW);
        return id;
    }

    private static void subscription(JdbcTemplate jdbc, UUID user) {
        jdbc.update("insert into push_subscriptions (id,user_id,endpoint,p256dh,auth,created_at) values (?,?,?,'p','a',?)",
                UUID.randomUUID(), user, "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID(), NOW);
    }

    private static void settings(JdbcTemplate jdbc, UUID user, boolean push, long version) {
        jdbc.update("insert into notification_settings (user_id,push_enabled,version,updated_at) values (?,?,?,?)", user, push, version, NOW);
    }

    private static Map<String, Object> row(JdbcTemplate jdbc, UUID user) {
        return jdbc.queryForMap("select * from notification_settings where user_id = ?", user);
    }

    @Test
    @DisplayName("assinante desligado liga e sobe a version; assinante sem linha ganha a linha com os padrões; sem assinatura e já ligado ficam intactos")
    void religaAssinantes() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            assertThat(Flyway.configure().dataSource(ds).locations("classpath:db/migration").placeholders(java.util.Map.of("owner-email", "")).target("9.25").load().migrate().success).isTrue();
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            UUID desligado = user(jdbc, "desligado");
            subscription(jdbc, desligado);
            subscription(jdbc, desligado);
            settings(jdbc, desligado, false, 3);
            UUID semLinha = user(jdbc, "sem-linha");
            subscription(jdbc, semLinha);
            subscription(jdbc, semLinha);
            UUID semAssinatura = user(jdbc, "sem-assinatura");
            settings(jdbc, semAssinatura, false, 5);
            UUID jaLigado = user(jdbc, "ja-ligado");
            subscription(jdbc, jaLigado);
            settings(jdbc, jaLigado, true, 7);

            Flyway all = Flyway.configure().dataSource(ds).locations("classpath:db/migration").placeholders(java.util.Map.of("owner-email", "")).load();
            assertThat(all.migrate().success).isTrue();

            assertThat(row(jdbc, desligado)).containsEntry("push_enabled", true).containsEntry("version", 4L);
            Map<String, Object> criada = row(jdbc, semLinha);
            assertThat(criada).containsEntry("push_enabled", true).containsEntry("version", 1L).containsEntry("summary_frequency", "DAILY")
                    .containsEntry("summary_enabled", true).containsEntry("disabled_kinds", "");
            assertThat(criada.get("summary_time").toString()).isEqualTo("08:00:00");
            assertThat(criada.get("quiet_from").toString()).isEqualTo("22:00:00");
            assertThat(criada.get("quiet_to").toString()).isEqualTo("07:00:00");
            assertThat(row(jdbc, semAssinatura)).containsEntry("push_enabled", false).containsEntry("version", 5L);
            assertThat(row(jdbc, jaLigado)).containsEntry("push_enabled", true).containsEntry("version", 7L);
            assertThat(all.validateWithResult().validationSuccessful).isTrue();
        }
    }

    @Test
    @DisplayName("em banco vazio a migração roda sem erro e não cria linha alguma")
    void bancoVazio() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway all = Flyway.configure().dataSource(ds).locations("classpath:db/migration").placeholders(java.util.Map.of("owner-email", "")).load();

            assertThat(all.migrate().success).isTrue();

            assertThat(new JdbcTemplate(ds).queryForObject("select count(*) from notification_settings", Long.class)).isZero();
            assertThat(all.validateWithResult().validationSuccessful).isTrue();
        }
    }
}
