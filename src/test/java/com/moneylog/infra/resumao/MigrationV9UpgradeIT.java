package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.moneylog.util.PostgresTestcontainer;

@Tag("i")
@DisplayName("Migrações V9.00 a V9.21 contra um Postgres real, com dados legados por cima da V8.17")
class MigrationV9UpgradeIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));

    private static void posting(JdbcTemplate jdbc, UUID user, String source, UUID sourceId, String book, UUID bookId, long cents) {
        jdbc.update("insert into ledger_postings (id,user_id,source_type,source_id,book_type,book_id,competence,effective_date,amount_cents,currency,active,created_at,updated_at) "
                + "values (?,?,?,?,?,?,'2026-10','2026-10-02',?,'BRL',true,?,?)", UUID.randomUUID(), user, source, sourceId, book, bookId, cents, NOW, NOW);
    }

    @Test
    @DisplayName("V8.17 com dados -> V9.19 -> V9.21: todas as migrações rodam, defaults de P-29 aplicados, fontes de recebimento reescritas pela V9.20 sem mexer na soma do razão, Flyway valida e repetir não executa nada")
    void upgradeComDados() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway upToV8 = Flyway.configure().dataSource(ds).locations("classpath:db/migration").placeholders(java.util.Map.of("owner-email", "")).target("8.17").load();
            assertThat(upToV8.migrate().success).isTrue();

            JdbcTemplate jdbc = new JdbcTemplate(ds);
            UUID user = UUID.randomUUID();
            UUID account = UUID.randomUUID();
            UUID category = UUID.randomUUID();
            jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", user, "legado@example.com", NOW, NOW);
            jdbc.update("insert into accounts (id,user_id,name,type,opening_balance_cents,currency,opening_date,status,created_at,updated_at) "
                    + "values (?,?,'Conta','CHECKING',100000,'BRL','2026-10-01','ACTIVE',?,?)", account, user, NOW, NOW);
            jdbc.update("insert into categories (id,user_id,name,kind,created_at,updated_at) values (?,?,'Mercado','EXPENSE',?,?)", category, user, NOW, NOW);
            for (int i = 0; i < 5; i++) {
                UUID tx = UUID.randomUUID();
                jdbc.update("insert into transactions (id,user_id,type,status,amount_cents,currency,transaction_date,description,payment_method,account_id,category_id,source,created_at,updated_at) "
                        + "values (?,?,'EXPENSE','ACTIVE',500,'BRL','2026-10-02','legado','PIX',?,?,'MANUAL',?,?)", tx, user, account, category, NOW, NOW);
            }
            UUID recurrence = UUID.randomUUID();
            jdbc.update("insert into recurring_transactions (id,user_id,type,amount_cents,currency,description,category_id,account_id,payment_method,frequency,interval_count,start_date,active,created_at,updated_at) "
                    + "values (?,?,'EXPENSE',9000,'BRL','Aluguel',?,?,'PIX','MONTHLY',1,'2026-10-05',true,?,?)", recurrence, user, category, account, NOW, NOW);

            Flyway upToV919 = Flyway.configure().dataSource(ds).locations("classpath:db/migration").placeholders(java.util.Map.of("owner-email", "")).target("9.19").load();
            assertThat(upToV919.migrate().success).isTrue();
            assertThat(jdbc.queryForList("select distinct nature from transactions where type = 'EXPENSE'", String.class)).containsExactly("VARIABLE");
            assertThat(jdbc.queryForObject("select nature from recurring_transactions where id = ?", String.class, recurrence)).isEqualTo("FIXED");
            assertThat(jdbc.queryForObject("select count(*) from transactions where payment_method is null and type = 'EXPENSE'", Long.class)).isZero();

            UUID person = UUID.randomUUID();
            UUID adjustment = UUID.randomUUID();
            UUID inBalance = UUID.randomUUID();
            UUID viaAccount = UUID.randomUUID();
            posting(jdbc, user, "THIRD_PARTY_RECEIPT", adjustment, "EXPENSE", category, 500);
            posting(jdbc, user, "THIRD_PARTY_RECEIPT", adjustment, "RECEIVABLE", person, -500);
            posting(jdbc, user, "THIRD_PARTY_RECEIPT", inBalance, "EQUITY", null, 300);
            posting(jdbc, user, "THIRD_PARTY_RECEIPT", inBalance, "RECEIVABLE", person, -300);
            posting(jdbc, user, "THIRD_PARTY_RECEIPT", viaAccount, "ACCOUNT", account, 200);
            posting(jdbc, user, "THIRD_PARTY_RECEIPT", viaAccount, "RECEIVABLE", person, -200);
            long sumBefore = jdbc.queryForObject("select sum(amount_cents) from ledger_postings", Long.class);
            long countBefore = jdbc.queryForObject("select count(*) from ledger_postings", Long.class);

            Flyway all = Flyway.configure().dataSource(ds).locations("classpath:db/migration").placeholders(java.util.Map.of("owner-email", "")).load();
            var result = all.migrate();

            assertThat(result.success).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from ledger_postings", Long.class)).isEqualTo(countBefore);
            assertThat(jdbc.queryForObject("select sum(amount_cents) from ledger_postings", Long.class)).isEqualTo(sumBefore);
            assertThat(jdbc.queryForObject("select distinct source_type from ledger_postings where source_id = ?", String.class, adjustment)).isEqualTo("THIRD_PARTY_REFUND_ADJUSTMENT");
            assertThat(jdbc.queryForObject("select distinct source_type from ledger_postings where source_id = ?", String.class, inBalance)).isEqualTo("THIRD_PARTY_RECEIPT_IN_BALANCE");
            assertThat(jdbc.queryForObject("select distinct source_type from ledger_postings where source_id = ?", String.class, viaAccount)).isEqualTo("THIRD_PARTY_RECEIPT");
            assertThat(all.validateWithResult().validationSuccessful).isTrue();
            assertThat(all.migrate().migrationsExecuted).isZero();
            List<MigrationInfo> applied = List.of(all.info().all());
            assertThat(applied).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
            assertThat(applied.stream().map(i -> i.getVersion().getVersion())).contains("9.00", "9.13", "9.15", "9.16", "9.20", "9.21");
            assertThat(applied.stream().map(i -> i.getVersion().getVersion()).filter(v -> v.equals("9.14") || v.equals("9.17")))
                    .as("lacunas de numeração V9.14 e V9.17 (achado L1 da revisão)").isEmpty();

            assertThatThrownBy(() -> jdbc.update("insert into ledger_postings (id,user_id,source_type,source_id,book_type,competence,effective_date,amount_cents,currency,active,created_at,updated_at) "
                    + "values (?,?,'FONTE_INEXISTENTE',?,'EQUITY','2026-10','2026-10-02',1,'BRL',true,?,?)", UUID.randomUUID(), user, UUID.randomUUID(), NOW, NOW))
                    .hasMessageContaining("ck_ledger_postings_source_type");
            assertThat(jdbc.queryForObject("select count(*) from credit_cards where auto_debit is not null", Long.class)).isZero();
        }
    }
}
