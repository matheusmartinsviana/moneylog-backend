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
 * T43 (QA), critério 8: caminho de upgrade. Aplica V1.00..V1.08 num banco vazio, grava dados do usuário A e B (como se
 * fossem da Etapa 1), migra para V2.04 e prova: ordem e checksums, dados intactos, FKs compostas valendo sobre os dados
 * migrados, e as tabelas novas (V2.01, V2.03, V2.04) com as barreiras entre usuários.
 */
@Tag("i")
@DisplayName("QA T43: migrações V2.00 a V2.04 sobre dados da V1")
class MigrationUpgradeQaIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));

    @Test
    @DisplayName("V1.08 com dados -> V2.04: ordem 1.00..2.04, dados intactos, FKs compostas e tabelas novas")
    void deveMigrarComDados() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway upToV1 = Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("1.08").load();
            assertThat(upToV1.migrate().migrationsExecuted).isEqualTo(9);

            JdbcTemplate jdbc = new JdbcTemplate(ds);
            UUID ua = UUID.randomUUID();
            UUID ub = UUID.randomUUID();
            jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", ua, "a@x.com", NOW, NOW);
            jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", ub, "b@x.com", NOW, NOW);
            UUID accA = UUID.randomUUID();
            UUID accB = UUID.randomUUID();
            UUID catA = UUID.randomUUID();
            for (Object[] a : new Object[][] {{accA, ua}, {accB, ub}}) {
                jdbc.update("insert into accounts (id,user_id,name,type,opening_balance_cents,currency,opening_date,status,created_at,updated_at) "
                        + "values (?,?,'Conta','CHECKING',1000,'BRL','2026-10-01','ACTIVE',?,?)", a[0], a[1], NOW, NOW);
            }
            jdbc.update("insert into categories (id,user_id,name,kind,created_at,updated_at) values (?,?,'Alim','EXPENSE',?,?)", catA, ua, NOW, NOW);
            UUID tx = UUID.randomUUID();
            jdbc.update("insert into transactions (id,user_id,type,status,amount_cents,currency,transaction_date,description,payment_method,account_id,category_id,source,created_at,updated_at) "
                    + "values (?,?,'EXPENSE','ACTIVE',500,'BRL','2026-10-02','x','PIX',?,?,'MANUAL',?,?)", tx, ua, accA, catA, NOW, NOW);
            jdbc.update("insert into ledger_postings (id,user_id,source_type,source_id,transaction_id,book_type,book_id,competence,effective_date,amount_cents,currency,active,created_at,updated_at) "
                    + "values (?,?,'TRANSACTION',?,?,'ACCOUNT',?,'2026-10','2026-10-02',-500,'BRL',true,?,?)", UUID.randomUUID(), ua, tx, tx, accA, NOW, NOW);
            jdbc.update("insert into ledger_postings (id,user_id,source_type,source_id,transaction_id,book_type,book_id,competence,effective_date,amount_cents,currency,active,created_at,updated_at) "
                    + "values (?,?,'TRANSACTION',?,?,'EXPENSE',?,'2026-10','2026-10-02',500,'BRL',true,?,?)", UUID.randomUUID(), ua, tx, tx, catA, NOW, NOW);

            Flyway all = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
            var result = all.migrate();
            assertThat(result.migrationsExecuted).isEqualTo(38); // V8.17 (dispensas de insights, ADR-019) + V8.16 (fotos das metas e destaque, ADR-018) + V8.15 (competência de renda, ADR-017) + V8.14 (metas de medida e plano, ADR-016) + V8.13 (fatura histórica) + V8.12 (limite opcional do cartão) +  V8.11 (recebimentos de terceiros) + V8.10 (senha do dono, ADR-015) + V8.09 (fechamento confirmado do cartão) + V8.0x (senha do dono e primeiro acesso, ADR-015) + V8.05 a V8.08 (conta a definir, compromissos acompanhados, despesa de terceiro) + V8.02 a V8.04 (visão de dívidas) + V7.00 a V7.03, V8.00 e V8.01 (Etapas 7 e 8, FK composta do lote) + 2.00 a 2.06 (2.05 índices B5 e 2.06 teto da sessão B7), 3.01, 3.02 (índices da Etapa 3), 4.00 a 4.02 (Etapa 4), 5.00 (metas, Etapa 5) e 6.00, 6.01, 6.02 (notas, unaccent e chave de nome dos wikilinks, Etapa 6)
            assertThat(result.success).isTrue();
            List<String> versions = jdbc.queryForList("select version from flyway_schema_history where version is not null order by installed_rank", String.class);
            assertThat(versions).containsExactly("1.00", "1.01", "1.02", "1.03", "1.04", "1.05", "1.06", "1.07", "1.08", "2.00", "2.01", "2.02", "2.03", "2.04", "2.05", "2.06", "3.01", "3.02", "4.00", "4.01", "4.02", "5.00", "6.00", "6.01", "6.02", "7.00", "7.01", "7.02", "7.03", "8.00", "8.01", "8.02", "8.03", "8.04", "8.05", "8.06", "8.07", "8.08", "8.09", "8.10", "8.11", "8.12", "8.13", "8.14", "8.15", "8.16", "8.17");
            assertThat(all.validateWithResult().validationSuccessful).as("checksums").isTrue();
            assertThat(all.migrate().migrationsExecuted).as("re-execução é no-op").isZero();

            // dados intactos
            assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from transactions", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select sum(amount_cents) from ledger_postings", Long.class)).isEqualTo(0L);
            assertThat(jdbc.queryForObject("select system_key from categories", String.class)).isNull();

            // FKs compostas valem sobre os dados migrados
            UUID txB = UUID.randomUUID();
            assertThatThrownBy(() -> jdbc.update("insert into transactions (id,user_id,type,status,amount_cents,currency,transaction_date,description,payment_method,account_id,category_id,source,created_at,updated_at) "
                    + "values (?,?,'EXPENSE','ACTIVE',1,'BRL','2026-10-02','x','PIX',?,?,'MANUAL',?,?)", txB, ub, accA, catA, NOW, NOW))
                    .hasMessageContaining("fk_transactions_account_id_user");
            assertThatThrownBy(() -> jdbc.update("insert into ledger_postings (id,user_id,source_type,source_id,transaction_id,book_type,book_id,competence,effective_date,amount_cents,currency,active,created_at,updated_at) "
                    + "values (?,?,'TRANSACTION',?,?,'ACCOUNT',?,'2026-10','2026-10-02',1,'BRL',true,?,?)", UUID.randomUUID(), ub, tx, tx, accB, NOW, NOW))
                    .hasMessageContaining("violates foreign key constraint");

            // V2.01: o sucessor do refresh token é do mesmo usuário
            UUID ra = UUID.randomUUID();
            UUID rb = UUID.randomUUID();
            String sqlRt = "insert into refresh_tokens (id,user_id,family_id,token_hash,created_at,updated_at,expires_at,replaced_by,replaced_at) values (?,?,?,?,?,?,?,?,?)";
            Timestamp later = Timestamp.from(Instant.parse("2026-11-10T12:00:00Z"));
            jdbc.update(sqlRt, rb, ub, UUID.randomUUID(), "b".repeat(64), NOW, NOW, later, null, null);
            assertThatThrownBy(() -> jdbc.update(sqlRt, ra, ua, UUID.randomUUID(), "a".repeat(64), NOW, NOW, later, rb, NOW))
                    .hasMessageContaining("fk_refresh_tokens_replaced_by_id_user");
            assertThatThrownBy(() -> jdbc.update(sqlRt, UUID.randomUUID(), ua, UUID.randomUUID(), "b".repeat(64), NOW, NOW, later, null, null))
                    .hasMessageContaining("uq_refresh_tokens_token_hash");
            assertThatThrownBy(() -> jdbc.update(sqlRt, UUID.randomUUID(), ua, UUID.randomUUID(), "c".repeat(64), NOW, NOW, NOW, null, null))
                    .hasMessageContaining("ck_refresh_tokens_expiry");

            // V2.02: unicidade de nome entre ativas, sem diferenciar caixa; arquivada libera o nome
            assertThatThrownBy(() -> jdbc.update("insert into categories (id,user_id,name,kind,created_at,updated_at) values (?,?,'ALIM','EXPENSE',?,?)", UUID.randomUUID(), ua, NOW, NOW))
                    .hasMessageContaining("uq_categories_user_kind_name_active");
            jdbc.update("insert into categories (id,user_id,name,kind,created_at,updated_at) values (?,?,'ALIM','INCOME',?,?)", UUID.randomUUID(), ua, NOW, NOW);
            jdbc.update("insert into categories (id,user_id,name,kind,created_at,updated_at) values (?,?,'alim','EXPENSE',?,?)", UUID.randomUUID(), ub, NOW, NOW);

            // V2.03: chave única por (usuário, escopo, chave)
            UUID key = UUID.randomUUID();
            String sqlKey = "insert into idempotency_keys (id,user_id,scope,idempotency_key,request_hash,resource_id,created_at,updated_at) values (?,?,'POST /v1/transactions',?,?,?,?,?)";
            jdbc.update(sqlKey, UUID.randomUUID(), ua, key, "h".repeat(64), tx, NOW, NOW);
            jdbc.update(sqlKey, UUID.randomUUID(), ub, key, "h".repeat(64), txB, NOW, NOW);
            assertThatThrownBy(() -> jdbc.update(sqlKey, UUID.randomUUID(), ua, key, "h".repeat(64), tx, NOW, NOW))
                    .hasMessageContaining("uq_idempotency_keys_user_scope_key");

            // V2.04: a revisão é do mesmo usuário da movimentação e única por (transação, revisão)
            String sqlRev = "insert into transaction_revisions (id,user_id,transaction_id,revision,snapshot,edited_at,created_at,updated_at) values (?,?,?,?,'{}'::jsonb,?,?,?)";
            assertThatThrownBy(() -> jdbc.update(sqlRev, UUID.randomUUID(), ub, tx, 1, NOW, NOW, NOW))
                    .hasMessageContaining("fk_transaction_revisions_transaction_user");
            jdbc.update(sqlRev, UUID.randomUUID(), ua, tx, 1, NOW, NOW, NOW);
            assertThatThrownBy(() -> jdbc.update(sqlRev, UUID.randomUUID(), ua, tx, 1, NOW, NOW, NOW))
                    .hasMessageContaining("uq_transaction_revisions_transaction_revision");
            assertThatThrownBy(() -> jdbc.update(sqlRev, UUID.randomUUID(), ua, tx, 0, NOW, NOW, NOW))
                    .hasMessageContaining("ck_transaction_revisions_revision");
        }
    }

    @Test
    @DisplayName("V8.01 (BUG-QA-10) aplica sobre banco com lote e movimentação importada; dados intactos e FK composta vale")
    void deveAplicarV801ComDadosImportados() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway upTo800 = Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("8.00").load();
            upTo800.migrate();

            JdbcTemplate jdbc = new JdbcTemplate(ds);
            UUID ua = UUID.randomUUID();
            UUID ub = UUID.randomUUID();
            UUID acc = UUID.randomUUID();
            UUID batchA = UUID.randomUUID();
            UUID batchB = UUID.randomUUID();
            UUID tx = UUID.randomUUID();
            jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", ua, "a@x.com", NOW, NOW);
            jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", ub, "b@x.com", NOW, NOW);
            jdbc.update("insert into accounts (id,user_id,name,type,opening_balance_cents,currency,opening_date,status,created_at,updated_at) "
                    + "values (?,?,'Conta','CHECKING',1000,'BRL','2026-10-01','ACTIVE',?,?)", acc, ua, NOW, NOW);
            String sqlBatch = "insert into import_batches (id,user_id,account_id,format,file_sha256,status,created_at,updated_at) values (?,?,?,'CSV',?,'APPLIED',?,?)";
            jdbc.update(sqlBatch, batchA, ua, acc, "a".repeat(64), NOW, NOW);
            String sqlTx = "insert into transactions (id,user_id,type,status,amount_cents,currency,transaction_date,description,payment_method,account_id,source,import_batch_id,created_at,updated_at) "
                    + "values (?,?,'EXPENSE','ACTIVE',500,'BRL','2026-10-02','x','PIX',?,'IMPORT',?,?,?)";
            jdbc.update(sqlTx, tx, ua, acc, batchA, NOW, NOW);

            Flyway all = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
            assertThat(all.migrate().success).isTrue();

            assertThat(jdbc.queryForObject("select import_batch_id from transactions where id = ?", UUID.class, tx)).isEqualTo(batchA);
            assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname = 'fk_transactions_import_batch'", Integer.class)).isZero();
            jdbc.update("insert into accounts (id,user_id,name,type,opening_balance_cents,currency,opening_date,status,created_at,updated_at) "
                    + "values (?,?,'Conta','CHECKING',0,'BRL','2026-10-01','ACTIVE',?,?)", UUID.randomUUID(), ub, NOW, NOW);
            UUID accB = jdbc.queryForObject("select id from accounts where user_id = ?", UUID.class, ub);
            jdbc.update(sqlBatch, batchB, ub, accB, "b".repeat(64), NOW, NOW);
            assertThatThrownBy(() -> jdbc.update(sqlTx, UUID.randomUUID(), ua, acc, batchB, NOW, NOW))
                    .hasMessageContaining("fk_transactions_import_batch_id_user");
        }
    }
}
