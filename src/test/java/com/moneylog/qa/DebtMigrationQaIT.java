package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
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
 * T82 (QA), critério 5: V4.00 a V4.02 sobre uma base da Etapa 3 com dados (inclusive dívidas e pessoas escritas direto no banco, o
 * único jeito de existirem antes da Etapa 4). Os dados não mudam, os índices existem, são válidos, parciais como documentado e o
 * planejador os usa; V4.01 troca o índice único da origem por um comum (uma despesa, várias dívidas) e V4.02 cria o esquema das
 * recorrências com as restrições do domínio.
 */
@Tag("i")
@DisplayName("QA T82: migrações V4.00 a V4.02 sobre dados da Etapa 3")
class DebtMigrationQaIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));

    private static void tx(JdbcTemplate jdbc, UUID id, UUID user, String type, long cents, UUID account, UUID category, UUID debt, String source, UUID recurrence, String occurrence) {
        jdbc.update("insert into transactions (id,user_id,type,status,amount_cents,currency,transaction_date,description,payment_method,account_id,category_id,debt_id,source,"
                + "recurrence_id,occurrence_date,created_at,updated_at) values (?,?,?,'ACTIVE',?,'BRL','2026-10-02','x',?,?,?,?,?,?,?::date,?,?)", id, user, type, cents,
                "EXPENSE".equals(type) ? "PIX" : null, account, "EXPENSE".equals(type) ? category : null, debt, source, recurrence, occurrence, NOW, NOW);
    }

    @Test
    @DisplayName("V3.02 com dados (inclusive dívidas legadas) -> V4.02: 3 migrações, dados intactos, índices válidos/parciais e usados, origem compartilhada permitida só depois da V4.01, restrições da V4.02 valem, reexecução sem efeito")
    void deveMigrarSemPerda() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway upToV3 = Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("3.02").load();
            assertThat(upToV3.migrate().migrationsExecuted).isEqualTo(18);

            JdbcTemplate jdbc = new JdbcTemplate(ds);
            UUID user = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", user, "m@x.com", NOW, NOW);
            jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", other, "o@x.com", NOW, NOW);
            UUID acc = UUID.randomUUID();
            UUID cat = UUID.randomUUID();
            UUID otherCat = UUID.randomUUID();
            jdbc.update("insert into accounts (id,user_id,name,type,opening_balance_cents,currency,opening_date,status,created_at,updated_at) "
                    + "values (?,?,'Conta','CHECKING',100000,'BRL','2026-10-01','ACTIVE',?,?)", acc, user, NOW, NOW);
            jdbc.update("insert into categories (id,user_id,name,kind,created_at,updated_at) values (?,?,'Alim','EXPENSE',?,?)", cat, user, NOW, NOW);
            jdbc.update("insert into categories (id,user_id,name,kind,created_at,updated_at) values (?,?,'Outra','EXPENSE',?,?)", otherCat, other, NOW, NOW);
            for (int i = 0; i < 40; i++) {
                UUID t = UUID.randomUUID();
                tx(jdbc, t, user, "EXPENSE", 500, acc, cat, null, "MANUAL", null, null);
                jdbc.update("insert into ledger_postings (id,user_id,source_type,source_id,transaction_id,book_type,book_id,competence,effective_date,amount_cents,currency,active,created_at,updated_at) "
                        + "values (?,?,'TRANSACTION',?,?,'ACCOUNT',?,'2026-10','2026-10-02',-500,'BRL',true,?,?)", UUID.randomUUID(), user, t, t, acc, NOW, NOW);
            }
            // dívidas legadas escritas direto: uma sem movimentação e uma com origem
            UUID person = UUID.randomUUID();
            jdbc.update("insert into persons (id,user_id,name,archived,created_at,updated_at) values (?,?,'João',false,?,?)", person, user, NOW, NOW);
            UUID plain = UUID.randomUUID();
            jdbc.update("insert into debts (id,user_id,person_id,direction,origin,principal_cents,currency,description,debt_date,created_at,updated_at) "
                    + "values (?,?,?,'PAYABLE','WITHOUT_MOVEMENT',20000,'BRL','legado','2026-10-02',?,?)", plain, user, person, NOW, NOW);
            UUID origin = UUID.randomUUID();
            UUID withOrigin = UUID.randomUUID();
            tx(jdbc, origin, user, "LOAN_GIVEN", 30000, acc, null, null, "MANUAL", null, null);
            jdbc.update("insert into debts (id,user_id,person_id,direction,origin,principal_cents,currency,description,debt_date,origin_transaction_id,created_at,updated_at) "
                    + "values (?,?,?,'RECEIVABLE','WITH_MOVEMENT',30000,'BRL','legado','2026-10-02',?,?,?)", withOrigin, user, person, origin, NOW, NOW);
            // antes da V4.01, duas dívidas com a mesma origem são recusadas pelo índice único
            UUID twin = UUID.randomUUID();
            assertThatThrownBy(() -> jdbc.update("insert into debts (id,user_id,person_id,direction,origin,principal_cents,currency,description,debt_date,origin_transaction_id,created_at,updated_at) "
                    + "values (?,?,?,'RECEIVABLE','WITH_MOVEMENT',100,'BRL','x','2026-10-02',?,?,?)", twin, user, person, origin, NOW, NOW)).hasMessageContaining("uq_debts_origin_transaction");

            long txBefore = jdbc.queryForObject("select count(*) from transactions", Long.class);
            long sumBefore = jdbc.queryForObject("select sum(amount_cents) from ledger_postings", Long.class);
            long debtsBefore = jdbc.queryForObject("select count(*) from debts", Long.class);

            Flyway all = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
            var result = all.migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(24); // V8.12 (limite opcional do cartão) +  V8.11 (recebimentos de terceiros) + V8.10 (senha do dono, ADR-015) + V8.09 (fechamento confirmado do cartão) + V8.0x (ADR-015) + V8.05 a V8.08 (ADR-014) + V8.02 a V8.04 + V4.00 a V4.02, V5.00 e V6.00, V6.01, V6.02, V7.00 a V7.03 e V8.00 e V8.01 (Etapas 7 e 8)
            assertThat(all.validateWithResult().validationSuccessful).isTrue();
            assertThat(all.migrate().migrationsExecuted).isZero();

            assertThat(jdbc.queryForObject("select count(*) from transactions", Long.class)).isEqualTo(txBefore);
            assertThat(jdbc.queryForObject("select sum(amount_cents) from ledger_postings", Long.class)).isEqualTo(sumBefore);
            assertThat(jdbc.queryForObject("select count(*) from debts", Long.class)).isEqualTo(debtsBefore);
            assertThat(jdbc.queryForObject("select category_id from debts where id = ?", UUID.class, plain)).isNull();

            // índices
            List<String> idx = jdbc.queryForList("select indexname || ' :: ' || indexdef from pg_indexes where indexname in ('ix_debts_user_person','ix_transactions_debt',"
                    + "'ix_debts_origin_transaction','ix_recurring_transactions_user','ix_recurrence_skips_user','uq_debts_origin_transaction')", String.class);
            assertThat(idx.stream().anyMatch(s -> s.startsWith("uq_debts_origin_transaction"))).as("o índice único antigo saiu").isFalse();
            assertThat(idx).hasSize(5);
            assertThat(idx.stream().filter(s -> s.startsWith("ix_debts_user_person")).findFirst().orElseThrow()).contains("(user_id, person_id)");
            assertThat(idx.stream().filter(s -> s.startsWith("ix_transactions_debt")).findFirst().orElseThrow()).contains("(debt_id)").contains("debt_id IS NOT NULL");
            assertThat(idx.stream().filter(s -> s.startsWith("ix_debts_origin_transaction")).findFirst().orElseThrow()).contains("(origin_transaction_id)").doesNotContain("UNIQUE");
            assertThat(jdbc.queryForObject("select count(*) from pg_index i join pg_class c on c.oid = i.indexrelid where c.relname in ('ix_debts_user_person','ix_transactions_debt',"
                    + "'ix_debts_origin_transaction','ix_recurring_transactions_user','ix_recurrence_skips_user') and not i.indisvalid", Long.class)).isZero();
            jdbc.execute("analyze");
            List<String> plans = new java.util.ArrayList<>();
            jdbc.execute((java.sql.Connection c) -> {
                try (var st = c.createStatement()) {
                    st.execute("set enable_seqscan = off");
                    plans.add(plan(st, "select id from debts where user_id = '" + user + "' and person_id = '" + UUID.randomUUID() + "'"));
                    plans.add(plan(st, "select id from transactions where debt_id = '" + UUID.randomUUID() + "'"));
                    plans.add(plan(st, "select id from debts where origin_transaction_id = '" + UUID.randomUUID() + "'"));
                    plans.add(plan(st, "select id from recurring_transactions where user_id = '" + user + "'"));
                    plans.add(plan(st, "select id from recurrence_skips where user_id = '" + user + "'"));
                }
                return null;
            });
            assertThat(plans.get(0)).contains("ix_debts_user_person");
            assertThat(plans.get(1)).contains("ix_transactions_debt");
            assertThat(plans.get(2)).contains("ix_debts_origin_transaction");
            assertThat(plans.get(3)).contains("ix_recurring_transactions_user");
            assertThat(plans.get(4)).contains("ix_recurrence_skips_user");

            // V4.01: várias dívidas na mesma origem; categoria só em PAYABLE sem movimentação, do mesmo usuário
            jdbc.update("insert into debts (id,user_id,person_id,direction,origin,principal_cents,currency,description,debt_date,origin_transaction_id,created_at,updated_at) "
                    + "values (?,?,?,'RECEIVABLE','WITH_MOVEMENT',100,'BRL','x','2026-10-02',?,?,?)", twin, user, person, origin, NOW, NOW);
            jdbc.update("insert into debts (id,user_id,person_id,direction,origin,principal_cents,currency,description,debt_date,category_id,created_at,updated_at) "
                    + "values (?,?,?,'PAYABLE','WITHOUT_MOVEMENT',100,'BRL','x','2026-10-02',?,?,?)", UUID.randomUUID(), user, person, cat, NOW, NOW);
            assertThatThrownBy(() -> jdbc.update("insert into debts (id,user_id,person_id,direction,origin,principal_cents,currency,description,debt_date,category_id,created_at,updated_at) "
                    + "values (?,?,?,'RECEIVABLE','WITHOUT_MOVEMENT',100,'BRL','x','2026-10-02',?,?,?)", UUID.randomUUID(), user, person, cat, NOW, NOW)).hasMessageContaining("ck_debts_category");
            assertThatThrownBy(() -> jdbc.update("insert into debts (id,user_id,person_id,direction,origin,principal_cents,currency,description,debt_date,category_id,created_at,updated_at) "
                    + "values (?,?,?,'PAYABLE','WITHOUT_MOVEMENT',100,'BRL','x','2026-10-02',?,?,?)", UUID.randomUUID(), user, person, otherCat, NOW, NOW)).hasMessageContaining("fk_debts_category_id_user");

            // V4.02: recorrência válida e as restrições do esquema
            UUID rec = UUID.randomUUID();
            insertRecurrence(jdbc, rec, user, "EXPENSE", 11900, acc, null, "PIX", "MONTHLY", 1, null, "2026-10-10", null, null);
            assertThatThrownBy(() -> insertRecurrence(jdbc, UUID.randomUUID(), user, "EXPENSE", 0, acc, null, "PIX", "MONTHLY", 1, null, "2026-10-10", null, null)).hasMessageContaining("amount_positive");
            assertThatThrownBy(() -> insertRecurrence(jdbc, UUID.randomUUID(), user, "EXPENSE", 100, acc, null, "PIX", "CUSTOM", 1, null, "2026-10-10", null, null)).hasMessageContaining("ck_recurring_transactions_custom");
            assertThatThrownBy(() -> insertRecurrence(jdbc, UUID.randomUUID(), user, "EXPENSE", 100, acc, null, "PIX", "MONTHLY", 2, "WEEKS", "2026-10-10", null, null)).hasMessageContaining("ck_recurring_transactions_custom");
            assertThatThrownBy(() -> insertRecurrence(jdbc, UUID.randomUUID(), user, "EXPENSE", 100, acc, null, "PIX", "MONTHLY", 1, null, "2026-10-10", "2026-10-09", null)).hasMessageContaining("ck_recurring_transactions_period");
            assertThatThrownBy(() -> insertRecurrence(jdbc, UUID.randomUUID(), user, "EXPENSE", 100, acc, null, "PIX", "MONTHLY", 1, null, "2026-10-10", null, 0)).hasMessageContaining("ck_recurring_transactions_max");
            assertThatThrownBy(() -> insertRecurrence(jdbc, UUID.randomUUID(), user, "EXPENSE", 100, acc, null, null, "MONTHLY", 1, null, "2026-10-10", null, null)).hasMessageContaining("ck_recurring_transactions_target");
            assertThatThrownBy(() -> insertRecurrence(jdbc, UUID.randomUUID(), other, "EXPENSE", 100, acc, null, "PIX", "MONTHLY", 1, null, "2026-10-10", null, null)).hasMessageContaining("fk_recurring_transactions_account");
            // pulada: única por (recorrência, data); de outro usuário não vale
            jdbc.update("insert into recurrence_skips (id,user_id,recurrence_id,occurrence_date,created_at,updated_at) values (?,?,?,'2026-11-10',?,?)", UUID.randomUUID(), user, rec, NOW, NOW);
            assertThatThrownBy(() -> jdbc.update("insert into recurrence_skips (id,user_id,recurrence_id,occurrence_date,created_at,updated_at) values (?,?,?,'2026-11-10',?,?)", UUID.randomUUID(), user, rec, NOW, NOW))
                    .hasMessageContaining("uq_recurrence_skips_recurrence_date");
            assertThatThrownBy(() -> jdbc.update("insert into recurrence_skips (id,user_id,recurrence_id,occurrence_date,created_at,updated_at) values (?,?,?,'2026-12-10',?,?)", UUID.randomUUID(), other, rec, NOW, NOW))
                    .hasMessageContaining("fk_recurrence_skips_recurrence_id_user");
            // movimentação realizada: duas ativas para a mesma (recorrência, data) são recusadas; a estornada não conta
            UUID t1 = UUID.randomUUID();
            tx(jdbc, t1, user, "EXPENSE", 11900, acc, cat, null, "RECURRENCE", rec, "2026-10-10");
            assertThatThrownBy(() -> tx(jdbc, UUID.randomUUID(), user, "EXPENSE", 11900, acc, cat, null, "RECURRENCE", rec, "2026-10-10")).hasMessageContaining("uq_transactions_recurrence_occurrence");
            jdbc.update("update transactions set status='VOIDED', voided_at=?, void_reason='x' where id = ?", NOW, t1);
            tx(jdbc, UUID.randomUUID(), user, "EXPENSE", 11900, acc, cat, null, "RECURRENCE", rec, "2026-10-10");
            // a FK de transactions.recurrence_id: recorrência inexistente é recusada
            assertThatThrownBy(() -> tx(jdbc, UUID.randomUUID(), user, "EXPENSE", 100, acc, cat, null, "RECURRENCE", UUID.randomUUID(), "2026-10-11")).hasMessageContaining("fk_transactions_recurrence_id_user");
        }
    }

    @Test
    @DisplayName("V4.02 sobre uma base com movimentação que já tinha recurrence_id solto (coluna sem FK desde a V1.06): documenta o efeito (a FK nova recusa o dado órfão)")
    void recurrenceIdSoltoAntigo() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway upToV3 = Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("4.01").load();
            upToV3.migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            UUID user = UUID.randomUUID();
            jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", user, "m@x.com", NOW, NOW);
            UUID acc = UUID.randomUUID();
            UUID cat = UUID.randomUUID();
            jdbc.update("insert into accounts (id,user_id,name,type,opening_balance_cents,currency,opening_date,status,created_at,updated_at) "
                    + "values (?,?,'Conta','CHECKING',100000,'BRL','2026-10-01','ACTIVE',?,?)", acc, user, NOW, NOW);
            jdbc.update("insert into categories (id,user_id,name,kind,created_at,updated_at) values (?,?,'Alim','EXPENSE',?,?)", cat, user, NOW, NOW);
            tx(jdbc, UUID.randomUUID(), user, "EXPENSE", 500, acc, cat, null, "RECURRENCE", UUID.randomUUID(), "2026-10-02");
            Flyway all = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
            assertThatThrownBy(all::migrate).hasMessageContaining("fk_transactions_recurrence_id_user");
        }
    }

    private static void insertRecurrence(JdbcTemplate jdbc, UUID id, UUID user, String type, long cents, UUID account, UUID card, String method, String frequency, int interval,
                                         String unit, String start, String end, Integer max) {
        jdbc.update("insert into recurring_transactions (id,user_id,type,amount_cents,currency,description,category_id,account_id,card_id,payment_method,frequency,interval_count,"
                + "interval_unit,start_date,end_date,max_occurrences,active,created_at,updated_at) select ?,?,?,?,'BRL','Rec',c.id,?,?,?,?,?,?,?::date,?::date,?,true,?,? "
                + "from categories c where c.user_id = ? limit 1", id, user, type, cents, account, card, method, frequency, interval, unit, start, end, max, NOW, NOW,
                user.equals(user) ? user : user);
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
