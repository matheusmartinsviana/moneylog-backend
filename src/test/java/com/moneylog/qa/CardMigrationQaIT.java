package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

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
 * T61 (QA), critério 6: V3.01 e V3.02 sobre uma base da Etapa 2 com dados. Só índices: nenhum dado muda, os índices são
 * válidos, parciais como documentado e o planejador os usa nas consultas que justificam cada um.
 */
@Tag("i")
@DisplayName("QA T61: migrações V3.01 e V3.02 sobre dados da Etapa 2")
class CardMigrationQaIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));

    @Test
    @DisplayName("V2.06 com dados -> V3.02: 2 migrações, dados intactos, índices válidos e parciais, usados pelo planejador, reexecução sem efeito")
    void deveMigrarSemPerda() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway upToV2 = Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("2.06").load();
            assertThat(upToV2.migrate().migrationsExecuted).isEqualTo(16);

            JdbcTemplate jdbc = new JdbcTemplate(ds);
            UUID user = UUID.randomUUID();
            jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", user, "m@x.com", NOW, NOW);
            UUID acc = UUID.randomUUID();
            UUID cat = UUID.randomUUID();
            jdbc.update("insert into accounts (id,user_id,name,type,opening_balance_cents,currency,opening_date,status,created_at,updated_at) "
                    + "values (?,?,'Conta','CHECKING',100000,'BRL','2026-10-01','ACTIVE',?,?)", acc, user, NOW, NOW);
            jdbc.update("insert into categories (id,user_id,name,kind,created_at,updated_at) values (?,?,'Alim','EXPENSE',?,?)", cat, user, NOW, NOW);
            for (int i = 0; i < 50; i++) {
                UUID tx = UUID.randomUUID();
                jdbc.update("insert into transactions (id,user_id,type,status,amount_cents,currency,transaction_date,description,payment_method,account_id,category_id,source,created_at,updated_at) "
                        + "values (?,?,'EXPENSE','ACTIVE',500,'BRL','2026-10-02','x','PIX',?,?,'MANUAL',?,?)", tx, user, acc, cat, NOW, NOW);
                jdbc.update("insert into ledger_postings (id,user_id,source_type,source_id,transaction_id,book_type,book_id,competence,effective_date,amount_cents,currency,active,created_at,updated_at) "
                        + "values (?,?,'TRANSACTION',?,?,'ACCOUNT',?,'2026-10','2026-10-02',-500,'BRL',true,?,?)", UUID.randomUUID(), user, tx, tx, acc, NOW, NOW);
            }
            long txBefore = jdbc.queryForObject("select count(*) from transactions", Long.class);
            long sumBefore = jdbc.queryForObject("select sum(amount_cents) from ledger_postings", Long.class);

            Flyway all = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
            var result = all.migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(28); // V8.14 (metas de medida e plano, ADR-016) + V8.13 (fatura histórica) + V8.12 (limite opcional do cartão) +  V8.11 (recebimentos de terceiros) + V8.10 (senha do dono, ADR-015) + V8.09 (fechamento confirmado do cartão) + V8.0x (ADR-015) + V8.05 a V8.08 (ADR-014) + V8.02 a V8.04 (visão de dívidas), V7.00 a V7.03, V8.00 e V8.01 (Etapas 7 e 8) + V3.01 e V3.02 (Etapa 3), V4.00 a V4.02 (Etapa 4), V5.00 (Etapa 5) e V6.00, V6.01, V6.02 (Etapa 6)
            assertThat(all.validateWithResult().validationSuccessful).isTrue();
            assertThat(all.migrate().migrationsExecuted).isZero();

            assertThat(jdbc.queryForObject("select count(*) from transactions", Long.class)).isEqualTo(txBefore);
            assertThat(jdbc.queryForObject("select sum(amount_cents) from ledger_postings", Long.class)).isEqualTo(sumBefore);

            List<String> indexes = jdbc.queryForList("select indexname || ' :: ' || indexdef from pg_indexes where indexname in "
                    + "('ix_transactions_user_card','ix_installments_user_transaction','ix_ledger_postings_installment','ix_transactions_invoice')", String.class);
            assertThat(indexes).hasSize(4);
            assertThat(indexes.stream().filter(s -> s.contains("ix_transactions_user_card")).findFirst().orElseThrow()).contains("(user_id, card_id)").contains("card_id IS NOT NULL");
            assertThat(indexes.stream().filter(s -> s.contains("ix_installments_user_transaction")).findFirst().orElseThrow()).contains("(user_id, transaction_id)");
            assertThat(indexes.stream().filter(s -> s.contains("ix_ledger_postings_installment")).findFirst().orElseThrow()).contains("installment_id IS NOT NULL");
            assertThat(indexes.stream().filter(s -> s.contains("ix_transactions_invoice")).findFirst().orElseThrow()).contains("invoice_id IS NOT NULL");
            assertThat(jdbc.queryForObject("select count(*) from pg_index i join pg_class c on c.oid = i.indexrelid where c.relname in "
                    + "('ix_transactions_user_card','ix_installments_user_transaction','ix_ledger_postings_installment','ix_transactions_invoice') and not i.indisvalid", Long.class)).isZero();

            // o planejador usa cada índice na consulta que o justifica (seqscan desligado para o teste não depender do tamanho da tabela)
            jdbc.execute("analyze");
            List<String> plans = new java.util.ArrayList<>();
            jdbc.execute((java.sql.Connection c) -> {
                try (var st = c.createStatement()) {
                    st.execute("set enable_seqscan = off");
                    plans.add(plan(st, "select id from transactions where user_id = '" + user + "' and card_id = '" + UUID.randomUUID() + "'"));
                    plans.add(plan(st, "select id from transaction_installments where user_id = '" + user + "' and transaction_id = '" + UUID.randomUUID() + "'"));
                    plans.add(plan(st, "select id from ledger_postings where installment_id = '" + UUID.randomUUID() + "'"));
                    plans.add(plan(st, "select id from transactions where invoice_id = '" + UUID.randomUUID() + "'"));
                }
                return null;
            });
            assertThat(plans.get(0)).contains("ix_transactions_user_card");
            assertThat(plans.get(1)).contains("ix_installments_user_transaction");
            assertThat(plans.get(2)).contains("ix_ledger_postings_installment");
            assertThat(plans.get(3)).contains("ix_transactions_invoice");

            // o esquema da Etapa 3 aceita e recusa o que o domínio espera: fechamento único por cartão e vencimento posterior
            assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_name = 'credit_card_invoices' and constraint_name like '%due_after_closing%'", Long.class)).isEqualTo(1L);
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
