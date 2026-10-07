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
 * T113 (QA), critério 5: V6.00 e V6.01 sobre uma base da Etapa 5 COM dados (contas, transações, razão, metas, cartão, faturas, pessoas, dívidas).
 * Os dados não mudam; {@code unaccent} funciona; as FKs compostas recusam alvo de outro usuário em todas as colunas; o índice único parcial da
 * lixeira permite reutilizar o caminho; ON DELETE CASCADE só na fatura vazia e na nota dona; as restrições do esquema valem; a reexecução não faz nada.
 */
@Tag("i")
@DisplayName("QA T113: migrações V6.00 e V6.01 sobre dados da Etapa 5")
class NoteMigrationQaIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));

    private static void note(JdbcTemplate jdbc, UUID id, UUID user, String path, String body, Timestamp deletedAt) {
        jdbc.update("insert into notes (id,user_id,path,path_key,title,body,deleted_at,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,0)", id, user, path, path.toLowerCase(), "t", body,
                deletedAt, NOW, NOW);
    }

    private static void wikilink(JdbcTemplate jdbc, UUID user, UUID noteId, UUID target, String text) {
        jdbc.update("insert into note_links (id,user_id,note_id,position,origin,target_type,target_note_id,target_text,created_at,updated_at) values (?,?,?,0,'WIKILINK','NOTE',?,?,?,?)",
                UUID.randomUUID(), user, noteId, target, text, NOW, NOW);
    }

    private static void explicit(JdbcTemplate jdbc, UUID user, UUID noteId, String type, String column, Object value) {
        jdbc.update("insert into note_links (id,user_id,note_id,position,origin,target_type," + column + ",created_at,updated_at) values (?,?,?,0,'EXPLICIT',?,?,?,?)",
                UUID.randomUUID(), user, noteId, type, value, NOW, NOW);
    }

    @Test
    @DisplayName("V5.00 com dados -> V6.00/V6.01: 2 migrações, dados intactos, unaccent disponível, FKs compostas por coluna, índice único parcial da lixeira, CASCADE só onde deve, CHECKs, índices usados, reexecução sem efeito")
    void deveMigrarSemPerda() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway upToV5 = Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("5.00").load();
            upToV5.migrate();
            assertThat(upToV5.info().current().getVersion().getVersion()).isEqualTo("5.00");
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertThatThrownBy(() -> jdbc.queryForObject("select count(*) from notes", Long.class)).hasMessageContaining("notes");

            UUID u1 = UUID.randomUUID();
            UUID u2 = UUID.randomUUID();
            for (UUID u : List.of(u1, u2)) {
                jdbc.update("insert into users (id,email,created_at,updated_at) values (?,?,?,?)", u, u + "@x.com", NOW, NOW);
            }
            UUID acc1 = UUID.randomUUID();
            UUID acc2 = UUID.randomUUID();
            UUID cat1 = UUID.randomUUID();
            UUID tx1 = UUID.randomUUID();
            UUID tx2 = UUID.randomUUID();
            UUID goal1 = UUID.randomUUID();
            UUID goal2 = UUID.randomUUID();
            UUID card1 = UUID.randomUUID();
            UUID card2 = UUID.randomUUID();
            UUID inv1 = UUID.randomUUID();
            UUID inv2 = UUID.randomUUID();
            UUID person1 = UUID.randomUUID();
            UUID person2 = UUID.randomUUID();
            UUID debt1 = UUID.randomUUID();
            UUID debt2 = UUID.randomUUID();
            for (Object[] a : new Object[][] {{acc1, u1}, {acc2, u2}}) {
                jdbc.update("insert into accounts (id,user_id,name,type,opening_balance_cents,currency,opening_date,status,created_at,updated_at) values (?,?,'Conta','CHECKING',100000,'BRL','2026-10-01','ACTIVE',?,?)",
                        a[0], a[1], NOW, NOW);
            }
            jdbc.update("insert into categories (id,user_id,name,kind,created_at,updated_at) values (?,?,'Alim','EXPENSE',?,?)", cat1, u1, NOW, NOW);
            for (Object[] t : new Object[][] {{tx1, u1, acc1}, {tx2, u2, acc2}}) {
                UUID cat = t[1].equals(u1) ? cat1 : null;
                jdbc.update("insert into transactions (id,user_id,type,status,amount_cents,currency,transaction_date,description,payment_method,account_id,category_id,source,created_at,updated_at) "
                        + "values (?,?,'EXPENSE','ACTIVE',500,'BRL','2026-10-02','x','PIX',?,?,'MANUAL',?,?)", t[0], t[1], t[2], cat, NOW, NOW);
                jdbc.update("insert into ledger_postings (id,user_id,source_type,source_id,transaction_id,book_type,book_id,competence,effective_date,amount_cents,currency,active,created_at,updated_at) "
                        + "values (?,?,'TRANSACTION',?,?,'ACCOUNT',?,'2026-10','2026-10-02',-500,'BRL',true,?,?)", UUID.randomUUID(), t[1], t[0], t[0], t[2], NOW, NOW);
            }
            for (Object[] g : new Object[][] {{goal1, u1}, {goal2, u2}}) {
                jdbc.update("insert into goals (id,user_id,name,target_cents,currency,priority,archived,created_at,updated_at,version) values (?,?,'Meta',100000,'BRL','HIGH',false,?,?,0)", g[0], g[1], NOW, NOW);
            }
            for (Object[] c : new Object[][] {{card1, u1}, {card2, u2}}) {
                jdbc.update("insert into credit_cards (id,user_id,name,limit_cents,currency,status,created_at,updated_at) values (?,?,'Cartão',100000,'BRL','ACTIVE',?,?)", c[0], c[1], NOW, NOW);
            }
            jdbc.update("insert into credit_card_invoices (id,user_id,card_id,period_start,closing_date,due_date,reference_month,created_at,updated_at) values (?,?,?,'2026-09-26','2026-10-25','2026-11-05','2026-10',?,?)", inv1, u1, card1, NOW, NOW);
            jdbc.update("insert into credit_card_invoices (id,user_id,card_id,period_start,closing_date,due_date,reference_month,created_at,updated_at) values (?,?,?,'2026-09-26','2026-10-25','2026-11-05','2026-10',?,?)", inv2, u2, card2, NOW, NOW);
            for (Object[] p : new Object[][] {{person1, u1}, {person2, u2}}) {
                jdbc.update("insert into persons (id,user_id,name,archived,created_at,updated_at) values (?,?,'Pessoa',false,?,?)", p[0], p[1], NOW, NOW);
            }
            jdbc.update("insert into debts (id,user_id,person_id,direction,origin,principal_cents,currency,debt_date,created_at,updated_at) values (?,?,?,'PAYABLE','WITHOUT_MOVEMENT',1000,'BRL','2026-10-10',?,?)", debt1, u1, person1, NOW, NOW);
            jdbc.update("insert into debts (id,user_id,person_id,direction,origin,principal_cents,currency,debt_date,created_at,updated_at) values (?,?,?,'PAYABLE','WITHOUT_MOVEMENT',1000,'BRL','2026-10-10',?,?)", debt2, u2, person2, NOW, NOW);

            long ledgerBefore = jdbc.queryForObject("select sum(amount_cents) from ledger_postings", Long.class);
            long txBefore = jdbc.queryForObject("select count(*) from transactions", Long.class);
            long invoicesBefore = jdbc.queryForObject("select count(*) from credit_card_invoices", Long.class);

            Flyway all = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
            var result = all.migrate();
            assertThat(result.success).isTrue();
            assertThat(result.migrationsExecuted).isEqualTo(12); // V8.02 a V8.04 + V6.00 a V6.02 + V7.00 a V7.03, V8.00 e V8.01
            assertThat(all.info().current().getVersion().getVersion()).isEqualTo("8.04");
            assertThat(all.validateWithResult().validationSuccessful).isTrue();
            assertThat(all.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("select sum(amount_cents) from ledger_postings", Long.class)).isEqualTo(ledgerBefore);
            assertThat(jdbc.queryForObject("select count(*) from transactions", Long.class)).isEqualTo(txBefore);
            assertThat(jdbc.queryForObject("select count(*) from credit_card_invoices", Long.class)).isEqualTo(invoicesBefore);
            assertThat(jdbc.queryForObject("select count(*) from notes", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from note_links", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from note_tags", Long.class)).isZero();

            // unaccent disponível (V6.01) e se comporta como a busca espera
            assertThat(jdbc.queryForObject("select unaccent('Ação e coração ÜNICODE')", String.class)).isEqualTo("Acao e coracao UNICODE");
            assertThat(jdbc.queryForObject("select 'Coração' ILIKE ('%' || unaccent('coracao') || '%') or unaccent('Coração') ILIKE unaccent('%coracao%')", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from pg_extension where extname = 'unaccent'", Long.class)).isEqualTo(1L);

            // índice único parcial da lixeira: o caminho (sem caixa) é único entre ativas, e a lixeira libera
            UUID n1 = UUID.randomUUID();
            UUID n2 = UUID.randomUUID();
            UUID n3 = UUID.randomUUID();
            note(jdbc, n1, u1, "Revisão.md", "x", null);
            assertThatThrownBy(() -> note(jdbc, UUID.randomUUID(), u1, "Revisão.md", "y", null)).hasMessageContaining("uq_notes_user_path_active");
            note(jdbc, n2, u2, "Revisão.md", "outro usuário pode", null);
            jdbc.update("update notes set deleted_at = ? where id = ?", NOW, n1);
            note(jdbc, n3, u1, "Revisão.md", "recriada depois da lixeira", null);
            note(jdbc, UUID.randomUUID(), u1, "Revisão.md", "outra na lixeira também", NOW);
            assertThatThrownBy(() -> jdbc.update("update notes set deleted_at = null where id = ?", n1)).hasMessageContaining("uq_notes_user_path_active");

            // restrições da nota
            assertThatThrownBy(() -> note(jdbc, UUID.randomUUID(), u1, "   ", "x", null)).hasMessageContaining("ck_notes_path_not_blank");
            assertThatThrownBy(() -> jdbc.update("insert into notes (id,user_id,path,path_key,title,body,created_at,updated_at) values (?,?,'a.md','a.md','  ','x',?,?)", UUID.randomUUID(), u1, NOW, NOW))
                    .hasMessageContaining("ck_notes_title_not_blank");
            assertThatThrownBy(() -> note(jdbc, UUID.randomUUID(), u1, "grande.md", "x".repeat(204_801), null)).hasMessageContaining("ck_notes_body_size");
            note(jdbc, UUID.randomUUID(), u1, "limite.md", "é".repeat(102_400), null);
            assertThatThrownBy(() -> note(jdbc, UUID.randomUUID(), UUID.randomUUID(), "sem-dono.md", "x", null)).hasMessageContaining("fk_notes_user");
            assertThatThrownBy(() -> jdbc.update("insert into notes (id,user_id,path,path_key,title,body,created_at,updated_at) values (?,?,?,?, 't', ?, ?, ?)", UUID.randomUUID(), u1, "z".repeat(256), "z".repeat(256), "x", NOW, NOW))
                    .isInstanceOf(Exception.class);

            // FKs compostas por coluna: o alvo de outro usuário é recusado em CADA tipo
            UUID mine = n3;
            explicit(jdbc, u1, mine, "TRANSACTION", "target_transaction_id", tx1);
            explicit(jdbc, u1, mine, "ACCOUNT", "target_account_id", acc1);
            explicit(jdbc, u1, mine, "CARD", "target_card_id", card1);
            explicit(jdbc, u1, mine, "INVOICE", "target_invoice_id", inv1);
            explicit(jdbc, u1, mine, "PERSON", "target_person_id", person1);
            explicit(jdbc, u1, mine, "DEBT", "target_debt_id", debt1);
            explicit(jdbc, u1, mine, "GOAL", "target_goal_id", goal1);
            explicit(jdbc, u1, mine, "MONTHLY_SUMMARY", "target_key", "2026-10");
            Object[][] foreign = {{"TRANSACTION", "target_transaction_id", tx2}, {"ACCOUNT", "target_account_id", acc2}, {"CARD", "target_card_id", card2}, {"INVOICE", "target_invoice_id", inv2},
                    {"PERSON", "target_person_id", person2}, {"DEBT", "target_debt_id", debt2}, {"GOAL", "target_goal_id", goal2}};
            for (Object[] f : foreign) {
                assertThatThrownBy(() -> explicit(jdbc, u1, mine, (String) f[0], (String) f[1], f[2])).as("%s de outro usuário", f[0]).hasMessageContaining("fk_note_links_" + f[1] + "_user");
            }
            // a nota (dona) e a tag também são do mesmo usuário
            assertThatThrownBy(() -> explicit(jdbc, u2, mine, "GOAL", "target_goal_id", goal2)).hasMessageContaining("fk_note_links_note_id_user");
            assertThatThrownBy(() -> jdbc.update("insert into note_tags (id,user_id,note_id,tag,created_at,updated_at) values (?,?,?,'x',?,?)", UUID.randomUUID(), u2, mine, NOW, NOW))
                    .hasMessageContaining("fk_note_tags_note_id_user");
            assertThatThrownBy(() -> wikilink(jdbc, u1, mine, n2, "Revisão")).as("wikilink para nota de outro usuário (a FK é deferida: confere no commit)").isInstanceOf(Exception.class);
            jdbc.update("delete from note_links where note_id = ?", mine);

            // CHECKs dos vínculos
            assertThatThrownBy(() -> explicit(jdbc, u1, mine, "GOAL", "target_key", "2026-10")).hasMessageContaining("ck_note_links");
            assertThatThrownBy(() -> explicit(jdbc, u1, mine, "MONTHLY_SUMMARY", "target_key", "2026-13")).hasMessageContaining("ck_note_links_key_format");
            assertThatThrownBy(() -> explicit(jdbc, u1, mine, "MONTHLY_SUMMARY", "target_key", "1999-12")).hasMessageContaining("ck_note_links_key_format");
            assertThatThrownBy(() -> explicit(jdbc, u1, mine, "MONTHLY_SUMMARY", "target_key", "2101-01")).hasMessageContaining("ck_note_links_key_format");
            assertThatThrownBy(() -> explicit(jdbc, u1, mine, "COISA", "target_goal_id", goal1)).hasMessageContaining("ck_note_links");
            assertThatThrownBy(() -> jdbc.update("insert into note_links (id,user_id,note_id,position,origin,target_type,target_goal_id,target_account_id,created_at,updated_at) values (?,?,?,0,'EXPLICIT','GOAL',?,?,?,?)",
                    UUID.randomUUID(), u1, mine, goal1, acc1, NOW, NOW)).hasMessageContaining("ck_note_links");
            assertThatThrownBy(() -> jdbc.update("insert into note_links (id,user_id,note_id,position,origin,target_type,created_at,updated_at) values (?,?,?,0,'EXPLICIT','GOAL',?,?)", UUID.randomUUID(), u1, mine, NOW, NOW))
                    .hasMessageContaining("ck_note_links_explicit_single_reference");
            assertThatThrownBy(() -> jdbc.update("insert into note_links (id,user_id,note_id,position,origin,target_type,target_goal_id,target_text,created_at,updated_at) values (?,?,?,0,'WIKILINK','NOTE',?,'x',?,?)",
                    UUID.randomUUID(), u1, mine, goal1, NOW, NOW)).hasMessageContaining("ck_note_links");
            assertThatThrownBy(() -> jdbc.update("insert into note_links (id,user_id,note_id,position,origin,target_type,created_at,updated_at) values (?,?,?,0,'WIKILINK','NOTE',?,?)", UUID.randomUUID(), u1, mine, NOW, NOW))
                    .hasMessageContaining("ck_note_links_wikilink");
            assertThatThrownBy(() -> jdbc.update("insert into note_links (id,user_id,note_id,position,origin,target_type,target_text,created_at,updated_at) values (?,?,?,0,'OUTRA','NOTE','x',?,?)", UUID.randomUUID(), u1, mine, NOW, NOW))
                    .hasMessageContaining("ck_note_links_origin");

            // CASCADE: a fatura vazia some e leva o vínculo; as demais entidades NÃO podem ser apagadas com vínculo; a nota dona leva tags e vínculos
            explicit(jdbc, u1, mine, "INVOICE", "target_invoice_id", inv1);
            explicit(jdbc, u1, mine, "GOAL", "target_goal_id", goal1);
            jdbc.update("insert into note_tags (id,user_id,note_id,tag,created_at,updated_at) values (?,?,?,'x',?,?)", UUID.randomUUID(), u1, mine, NOW, NOW);
            jdbc.update("delete from credit_card_invoices where id = ?", inv1);
            assertThat(jdbc.queryForObject("select count(*) from note_links where note_id = ? and target_invoice_id is not null", Long.class, mine)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from notes where id = ?", Long.class, mine)).isEqualTo(1L);
            assertThatThrownBy(() -> jdbc.update("delete from goals where id = ?", goal1)).hasMessageContaining("fk_note_links_target_goal_id_user");
            assertThatThrownBy(() -> jdbc.update("delete from persons where id = ?", person1)).isInstanceOf(Exception.class);
            explicit(jdbc, u1, mine, "TRANSACTION", "target_transaction_id", tx1);
            assertThatThrownBy(() -> jdbc.update("delete from transactions where id = ?", tx1)).isInstanceOf(Exception.class);
            jdbc.update("delete from notes where id = ?", mine);
            assertThat(jdbc.queryForObject("select count(*) from note_links where note_id = ?", Long.class, mine)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from note_tags where note_id = ?", Long.class, mine)).isZero();
            // apagar uma nota que outra nota cita (wikilink resolvido) é recusado no commit: o esvaziar da lixeira apaga os vínculos antes
            UUID citing = UUID.randomUUID();
            UUID cited = UUID.randomUUID();
            note(jdbc, citing, u1, "cita.md", "[[alvo]]", null);
            note(jdbc, cited, u1, "alvo.md", "x", null);
            wikilink(jdbc, u1, citing, cited, "alvo");
            assertThatThrownBy(() -> jdbc.update("delete from notes where id = ?", cited)).hasMessageContaining("fk_note_links_target_note_id_user");

            // índices existem, são válidos e o planejador os usa
            List<String> idx = jdbc.queryForList("select indexname from pg_indexes where tablename in ('notes','note_tags','note_links') and indexname like 'ix_%' order by 1", String.class);
            assertThat(idx).contains("ix_notes_user_updated", "ix_notes_user_deleted", "ix_note_tags_user_tag", "ix_note_links_note", "ix_note_links_user_target_note", "ix_note_links_user_target_key");
            assertThat(jdbc.queryForObject("select count(*) from pg_index i join pg_class c on c.oid = i.indexrelid where (c.relname like 'ix_note%' or c.relname like 'uq_note%') and not i.indisvalid", Long.class)).isZero();
            jdbc.execute("analyze");
            List<String> plans = new java.util.ArrayList<>();
            jdbc.execute((java.sql.Connection c) -> {
                try (var st = c.createStatement()) {
                    st.execute("set enable_seqscan = off");
                    plans.add(plan(st, "select distinct note_id from note_links where user_id = '" + u1 + "' and origin = 'WIKILINK' and target_note_id = '" + cited + "'"));
                    plans.add(plan(st, "select id from notes where user_id = '" + u1 + "' and deleted_at is null"));
                    plans.add(plan(st, "select id from notes where user_id = '" + u1 + "' and path_key = 'cita.md' and deleted_at is null"));
                    plans.add(plan(st, "select note_id from note_tags where user_id = '" + u1 + "' and tag = 'x'"));
                }
                return null;
            });
            assertThat(plans.get(0)).containsAnyOf("ix_note_links_user_target_note", "ix_note_links_note");
            assertThat(plans.get(1)).containsAnyOf("ix_notes_user_updated", "uq_notes_user_path_active");
            assertThat(plans.get(2)).contains("uq_notes_user_path_active");
            assertThat(plans.get(3)).contains("ix_note_tags_user_tag");
            // as lentes financeiras seguem intactas depois de tudo
            assertThat(jdbc.queryForObject("select sum(amount_cents) from ledger_postings", Long.class)).isEqualTo(ledgerBefore);
            assertThat(jdbc.queryForObject("select count(*) from transactions", Long.class)).isEqualTo(txBefore);
        }
    }

    @Test
    @DisplayName("V6.01 em base vazia e a cadeia completa de V1: migra sem erro, e CREATE EXTENSION IF NOT EXISTS unaccent é idempotente")
    void baseVazia() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse(PostgresTestcontainer.IMAGE))) {
            pg.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            Flyway all = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
            assertThat(all.migrate().success).isTrue();
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name in ('notes','note_tags','note_links')", Long.class)).isEqualTo(3L);
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS unaccent");
            assertThat(jdbc.queryForObject("select unaccent('é')", String.class)).isEqualTo("e");
            // V6.02 (S-278): chave de nome do wikilink e o índice parcial que o recálculo usa
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'note_links' and column_name = 'target_name_key'", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'ix_note_links_user_wikilink_name'", String.class))
                    .contains("(user_id, target_name_key)").contains("WIKILINK");
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
