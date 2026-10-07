package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

/**
 * T98 (QA), critério 6: V5.00 sobre uma base da Etapa 4 com dados (despesas, razão, dívidas, recorrência). Os dados não mudam, as tabelas
 * novas nascem vazias, os índices existem, são válidos e o planejador os usa, as restrições do esquema valem (valor, moeda, prioridade,
 * alvo, FKs compostas entre usuários) e a reexecução não faz nada.
 */
@Tag("i")
@DisplayName("QA T98: migração V5.00 sobre dados da Etapa 4")
class GoalMigrationQaIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));

    private static void goal(JdbcTemplate jdbc, UUID id, UUID user, String name, long target, String currency, String priority) {
        jdbc.update("insert into goals (id,user_id,name,target_cents,currency,priority,archived,created_at,updated_at,version) values (?,?,?,?,?,?,false,?,?,0)", id, user, name, target, currency,
                priority, NOW, NOW);
    }

    private static void reservation(JdbcTemplate jdbc, UUID id, UUID user, UUID goal, UUID account, long cents, String currency) {
        jdbc.update("insert into goal_reservations (id,user_id,goal_id,account_id,amount_cents,currency,reserved_on,created_at,updated_at) values (?,?,?,?,?,?,'2026-10-10',?,?)", id, user, goal,
                account, cents, currency, NOW, NOW);
    }

    @Test
    @DisplayName("V4.02 com dados -> V5.00: 1 migração, dados intactos, tabelas vazias, índices válidos e usados, restrições e FKs compostas valem, reexecução sem efeito")
    void deveMigrarSemPerda() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway upToV4 = Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("4.02").load();
            upToV4.migrate();
            assertThat(upToV4.info().current().getVersion().getVersion()).isEqualTo("4.02");

            JdbcTemplate jdbc = new JdbcTemplate(ds);
            UUID user = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", user, "m@x.com", NOW, NOW);
            jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", other, "o@x.com", NOW, NOW);
            UUID acc = UUID.randomUUID();
            UUID otherAcc = UUID.randomUUID();
            UUID cat = UUID.randomUUID();
            jdbc.update("insert into accounts (id,user_id,name,type,opening_balance_cents,currency,opening_date,status,created_at,updated_at) "
                    + "values (?,?,'Conta','CHECKING',100000,'BRL','2026-10-01','ACTIVE',?,?)", acc, user, NOW, NOW);
            jdbc.update("insert into accounts (id,user_id,name,type,opening_balance_cents,currency,opening_date,status,created_at,updated_at) "
                    + "values (?,?,'Conta B','CHECKING',100000,'BRL','2026-10-01','ACTIVE',?,?)", otherAcc, other, NOW, NOW);
            jdbc.update("insert into categories (id,user_id,name,kind,created_at,updated_at) values (?,?,'Alim','EXPENSE',?,?)", cat, user, NOW, NOW);
            for (int i = 0; i < 60; i++) {
                UUID t = UUID.randomUUID();
                jdbc.update("insert into transactions (id,user_id,type,status,amount_cents,currency,transaction_date,description,payment_method,account_id,category_id,source,created_at,updated_at) "
                        + "values (?,?,'EXPENSE','ACTIVE',500,'BRL','2026-10-02','x','PIX',?,?,'MANUAL',?,?)", t, user, acc, cat, NOW, NOW);
                jdbc.update("insert into ledger_postings (id,user_id,source_type,source_id,transaction_id,book_type,book_id,competence,effective_date,amount_cents,currency,active,created_at,updated_at) "
                        + "values (?,?,'TRANSACTION',?,?,'ACCOUNT',?,'2026-10','2026-10-02',-500,'BRL',true,?,?)", UUID.randomUUID(), user, t, t, acc, NOW, NOW);
            }
            long txBefore = jdbc.queryForObject("select count(*) from transactions", Long.class);
            long sumBefore = jdbc.queryForObject("select sum(amount_cents) from ledger_postings", Long.class);
            long accountsBefore = jdbc.queryForObject("select count(*) from accounts", Long.class);
            assertThatThrownBy(() -> jdbc.queryForObject("select count(*) from goals", Long.class)).hasMessageContaining("goals");

            Flyway all = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
            var result = all.migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(13); // V8.02 a V8.04 + V7.00 a V7.03, V8.00 e V8.01 (Etapas 7 e 8) + V5.00 (metas) e V6.00, V6.01, V6.02 (notas, Etapa 6)
            assertThat(all.info().applied()).extracting(i -> i.getVersion().getVersion()).contains("5.00");
            assertThat(all.info().current().getVersion().getVersion()).isEqualTo("8.04");
            assertThat(all.validateWithResult().validationSuccessful).isTrue();
            assertThat(all.migrate().migrationsExecuted).isZero();

            assertThat(jdbc.queryForObject("select count(*) from transactions", Long.class)).isEqualTo(txBefore);
            assertThat(jdbc.queryForObject("select sum(amount_cents) from ledger_postings", Long.class)).isEqualTo(sumBefore);
            assertThat(jdbc.queryForObject("select count(*) from accounts", Long.class)).isEqualTo(accountsBefore);
            assertThat(jdbc.queryForObject("select count(*) from goals", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from goal_reservations", Long.class)).isZero();

            // índices
            List<String> idx = jdbc.queryForList("select indexname || ' :: ' || indexdef from pg_indexes where indexname in ('ix_goals_user','ix_goal_reservations_user',"
                    + "'ix_goal_reservations_goal','ix_goal_reservations_account')", String.class);
            assertThat(idx).hasSize(4);
            assertThat(jdbc.queryForObject("select count(*) from pg_index i join pg_class c on c.oid = i.indexrelid where c.relname like 'ix_goal%' and not i.indisvalid", Long.class)).isZero();

            // restrições
            UUID g = UUID.randomUUID();
            goal(jdbc, g, user, "Meta", 100_000, "BRL", "HIGH");
            assertThatThrownBy(() -> goal(jdbc, UUID.randomUUID(), user, "  ", 100, "BRL", "HIGH")).hasMessageContaining("ck_goals_name_not_blank");
            assertThatThrownBy(() -> goal(jdbc, UUID.randomUUID(), user, "x", 0, "BRL", "HIGH")).hasMessageContaining("ck_goals_target_positive");
            assertThatThrownBy(() -> goal(jdbc, UUID.randomUUID(), user, "x", -5, "BRL", "HIGH")).hasMessageContaining("ck_goals_target_positive");
            assertThatThrownBy(() -> goal(jdbc, UUID.randomUUID(), user, "x", 100, "USD", "HIGH")).hasMessageContaining("ck_goals_currency");
            assertThatThrownBy(() -> goal(jdbc, UUID.randomUUID(), user, "x", 100, "BRL", "URGENT")).hasMessageContaining("ck_goals_priority");
            assertThatThrownBy(() -> goal(jdbc, UUID.randomUUID(), UUID.randomUUID(), "x", 100, "BRL", "HIGH")).hasMessageContaining("fk_goals_user");
            assertThatThrownBy(() -> jdbc.update("insert into goals (id,user_id,name,target_cents,currency,priority,created_at,updated_at) values (?,?,'x',100,'BRL','LOW',?,?)", g, user, NOW, NOW))
                    .hasMessageContaining("pk_goals");
            reservation(jdbc, UUID.randomUUID(), user, g, acc, 5_000, "BRL");
            reservation(jdbc, UUID.randomUUID(), user, g, acc, -2_000, "BRL");
            assertThatThrownBy(() -> reservation(jdbc, UUID.randomUUID(), user, g, acc, 0, "BRL")).hasMessageContaining("ck_goal_reservations_amount_not_zero");
            assertThatThrownBy(() -> reservation(jdbc, UUID.randomUUID(), user, g, acc, 100, "USD")).hasMessageContaining("ck_goal_reservations_currency");
            assertThatThrownBy(() -> reservation(jdbc, UUID.randomUUID(), other, g, otherAcc, 100, "BRL")).hasMessageContaining("fk_goal_reservations_goal_id_user");
            assertThatThrownBy(() -> reservation(jdbc, UUID.randomUUID(), user, g, otherAcc, 100, "BRL")).hasMessageContaining("fk_goal_reservations_account_id_user");
            assertThatThrownBy(() -> reservation(jdbc, UUID.randomUUID(), user, UUID.randomUUID(), acc, 100, "BRL")).hasMessageContaining("fk_goal_reservations_goal_id_user");
            // não dá para apagar a meta nem a conta com reservas (sem cascata silenciosa)
            assertThatThrownBy(() -> jdbc.update("delete from goals where id = ?", g)).hasMessageContaining("fk_goal_reservations_goal_id_user");
            assertThatThrownBy(() -> jdbc.update("delete from accounts where id = ?", acc)).isInstanceOf(Exception.class);
            // reserva não é lançamento: o razão não mudou
            assertThat(jdbc.queryForObject("select sum(amount_cents) from ledger_postings", Long.class)).isEqualTo(sumBefore);
            assertThat(jdbc.queryForObject("select count(*) from transactions", Long.class)).isEqualTo(txBefore);

            // planejador usa os índices
            jdbc.execute("analyze");
            List<String> plans = new ArrayList<>();
            jdbc.execute((java.sql.Connection c) -> {
                try (var st = c.createStatement()) {
                    st.execute("set enable_seqscan = off");
                    plans.add(plan(st, "select id from goals where user_id = '" + user + "'"));
                    plans.add(plan(st, "select id from goal_reservations where user_id = '" + user + "' and goal_id = '" + g + "'"));
                    plans.add(plan(st, "select id from goal_reservations where account_id = '" + acc + "'"));
                    plans.add(plan(st, "select id from goal_reservations where user_id = '" + user + "'"));
                }
                return null;
            });
            assertThat(plans.get(0)).contains("ix_goals_user");
            assertThat(plans.get(1)).containsAnyOf("ix_goal_reservations_goal", "ix_goal_reservations_user");
            assertThat(plans.get(2)).contains("ix_goal_reservations_account");
            assertThat(plans.get(3)).contains("ix_goal_reservations_user");
        }
    }

    @Test
    @DisplayName("V5.00 em base vazia (Etapa 4 sem dados) e a partir de V1: a cadeia completa migra sem erro e as tabelas existem")
    void baseVazia() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway all = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
            assertThat(all.migrate().success).isTrue();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name in ('goals','goal_reservations')", Long.class)).isEqualTo(2L);
            assertThat(all.info().applied()).extracting(i -> i.getVersion().getVersion()).contains("5.00");
        }
    }

    private static String plan(java.sql.Statement st, String sql) throws java.sql.SQLException {
        StringBuilder out = new StringBuilder();
        try (var rs = st.executeQuery("explain " + sql)) {
            while (rs.next()) {
                out.append(rs.getString(1)).append('\n');
            }
        }
        return out.toString();
    }
}
