package com.moneylog.infra.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.util.IntegrationTest;

@IntegrationTest
@DisplayName("Migrações Flyway iniciais (T11)")
class FlywayMigrationIT {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID CATEGORY = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private static final UUID CARD = UUID.fromString("00000000-0000-0000-0000-00000000d001");
    private static final UUID INVOICE = UUID.fromString("00000000-0000-0000-0000-00000000f001");
    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-00000000e001");
    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));
    private static final Date DAY = Date.valueOf(LocalDate.of(2026, 10, 10));
    private static final String KEY = "a".repeat(64);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("todas as constraints citadas existem em pg_constraint com o nome exato")
    void deveCriarConstraintsNomeadas() {
        List<String> constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = 'public'",
                String.class);
        assertThat(constraints).contains(
                "ck_transactions_amount_positive", "ck_installments_amount_positive",
                "uq_credit_card_invoices_card_closing", "ck_credit_card_invoices_period",
                "uq_installments_transaction_number", "ck_ledger_postings_transaction_source",
                "ck_ledger_postings_source_type", "ck_ledger_postings_book_type",
                "ck_debts_origin_transaction", "ck_debts_closure", "fk_debts_origin_transaction_id_user",
                "ck_transactions_type", "ck_transactions_status", "ck_transactions_source",
                "ck_debts_direction", "ck_credit_card_billing_rules_closing_day", "ck_credit_card_billing_rules_due_day",
                "uq_credit_cards_id_user", "fk_credit_card_billing_rules_card",
                "ck_transactions_installments", "ck_installments_total");
    }

    @Test
    @DisplayName("os índices citados existem em pg_indexes, com os parciais WHERE active / status ACTIVE")
    void deveCriarIndicesNomeados() {
        assertThat(indexDef("uq_transactions_user_dedup_active"))
                .contains("UNIQUE").contains("(user_id, dedup_key)").contains("'ACTIVE'").contains("dedup_key IS NOT NULL");
        assertThat(indexDef("uq_transactions_recurrence_occurrence")).contains("UNIQUE").contains("(recurrence_id, occurrence_date)");
        assertThat(indexDef("ix_debts_origin_transaction")).doesNotContain("UNIQUE").contains("origin_transaction_id IS NOT NULL");
        assertThat(indexDef("ix_ledger_postings_book")).contains("(user_id, book_type, book_id, effective_date)").contains("WHERE active");
        assertThat(indexDef("ix_ledger_postings_competence")).contains("(user_id, book_type, competence)").contains("WHERE active");
        assertThat(indexDef("ix_ledger_postings_source")).contains("(source_type, source_id)");
    }

    @Test
    @DisplayName("a FK da origem da dívida é DEFERRABLE INITIALLY DEFERRED")
    void deveTerFkDaOrigemDiferida() {
        Boolean deferred = jdbc.queryForObject(
                "SELECT condeferrable AND condeferred FROM pg_constraint WHERE conname = 'fk_debts_origin_transaction_id_user'", Boolean.class);
        assertThat(deferred).isTrue();
    }

    @Test
    @DisplayName("todas as tabelas têm id UUID sem default, user_id NOT NULL e timestamps TIMESTAMPTZ")
    void deveSeguirConvencoesDeColunas() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'"
                        + " AND table_name <> 'flyway_schema_history'", String.class);
        assertThat(tables).contains("users", "categories", "accounts", "credit_cards", "credit_card_billing_rules",
                "credit_card_invoices", "persons", "debts", "transactions", "transaction_installments", "ledger_postings");
        Set<String> keyedByUser = Set.of("import_mappings", "notification_settings", "ai_settings", "tracked_commitment_items", "goal_photo_blobs", "user_profile_photos");
        Set<String> noTimestamps = Set.of("import_lines", "tracked_commitment_items", "goal_photo_blobs", "user_profile_photos");
        Set<String> referenceData = Set.of("tax_tables", "tax_table_brackets", "tax_table_sources");
        Set<String> accessControl = Set.of("user_invitations");
        Set<String> v9 = Set.of("card_purchase_shares", "tax_tables", "tax_table_brackets", "tax_table_sources", "tracked_commitment_item_shares",
                "payroll_loan_applications", "purchase_groups", "purchase_group_members", "recurrence_shares", "income_profiles", "income_profile_deductions",
                "income_profile_occurrences", "transaction_foreign_charges", "income_occurrence_loans", "third_party_mark_migrations", "assets", "asset_balances");
        Set<String> appendOnly = Set.of("notifications", "push_subscriptions", "ai_previews", "ai_analyses", "categorization_rules", "third_party_expenses", "third_party_receipts", "insight_dismissals");
        for (String table : tables) {
            if (!keyedByUser.contains(table) && (!v9.contains(table) || hasColumn(table, "id"))) {
                assertThat(column(table, "id")).as(table + ".id").isEqualTo("uuid|NO|null");
            }
            if (!keyedByUser.contains(table) && !noTimestamps.contains(table) && (!v9.contains(table) || hasColumn(table, "created_at"))) {
                assertThat(column(table, "created_at")).as(table + ".created_at").startsWith("timestamp with time zone|NO");
            }
            if (!appendOnly.contains(table) && !noTimestamps.contains(table) && (!v9.contains(table) || hasColumn(table, "updated_at"))) {
                assertThat(column(table, "updated_at")).as(table + ".updated_at").startsWith("timestamp with time zone|NO");
            }
            if (!table.equals("users") && !referenceData.contains(table) && !accessControl.contains(table)) {
                assertThat(column(table, "user_id")).as(table + ".user_id").startsWith("uuid|NO");
            }
        }
        for (String editable : List.of("users", "categories", "accounts", "credit_cards", "credit_card_invoices", "persons",
                "debts", "transactions", "transaction_installments")) {
            assertThat(column(editable, "version")).as(editable + ".version").startsWith("bigint|NO");
        }
    }

    @Test
    @DisplayName("dinheiro só em *_cents BIGINT + currency CHAR(3); nenhuma coluna numeric/float")
    void deveGuardarDinheiroEmCentavos() {
        List<String> decimais = jdbc.queryForList(
                "SELECT table_name || '.' || column_name FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND data_type IN ('numeric', 'real', 'double precision', 'money')", String.class);
        assertThat(decimais).as("só a taxa de câmbio (metadado, ADR-025) é decimal; dinheiro é sempre centavos").containsExactlyInAnyOrder(
                "transaction_foreign_charges.fx_rate", "recurring_transactions.reference_fx_rate");
        List<String> cents = jdbc.queryForList(
                "SELECT table_name || '.' || column_name || ':' || data_type FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND column_name LIKE '%\\_cents'", String.class);
        assertThat(cents).isNotEmpty().allSatisfy(c -> assertThat(c).endsWith(":bigint"));
    }

    @Test
    @DisplayName("credit_card_invoices não tem coluna de status (S-37); debts não tem status derivado")
    void naoDeveGravarStatusDerivado() {
        assertThat(columnNames("credit_card_invoices"))
                .contains("period_start", "closing_date", "due_date", "reference_month")
                .doesNotContain("status");
        assertThat(columnNames("debts")).contains("origin", "origin_transaction_id", "closure", "closure_date", "closure_reason")
                .doesNotContain("status");
        assertThat(columnNames("transaction_installments")).contains("invoice_id", "late_entry", "canceled_at")
                .doesNotContain("status");
        assertThat(columnNames("transactions")).contains("invoice_id", "late_entry");
        assertThat(columnNames("ledger_postings")).contains("source_type", "source_id", "transaction_id", "installment_id",
                "book_type", "book_id", "competence", "effective_date", "amount_cents", "active", "deactivated_at",
                "deactivation_reason");
    }

    @ParameterizedTest(name = "amount_cents = {0}")
    @ValueSource(longs = {0, -100})
    @DisplayName("transactions rejeita amount_cents <= 0 (ck_transactions_amount_positive)")
    void deveRejeitarValorNaoPositivoEmTransactions(long cents) {
        inRollback(db -> {
            seedBase();
            assertViolation(() -> insertExpense(UUID.randomUUID(), cents, "ACTIVE", null), "ck_transactions_amount_positive");
        });
    }

    @ParameterizedTest(name = "amount_cents = {0}")
    @ValueSource(longs = {0, -1})
    @DisplayName("transaction_installments rejeita amount_cents <= 0 (ck_installments_amount_positive)")
    void deveRejeitarValorNaoPositivoEmParcelas(long cents) {
        inRollback(db -> {
            seedBase();
            UUID purchase = insertCardPurchase(UUID.randomUUID(), 6);
            assertViolation(() -> insertInstallment(purchase, 1, cents), "ck_installments_amount_positive");
        });
    }

    @Nested
    @DisplayName("faturas e parcelas")
    class Faturas {

        @Test
        @DisplayName("mesmo (card_id, closing_date) viola uq_credit_card_invoices_card_closing")
        void deveRejeitarFaturaDuplicadaPorFechamento() {
            inRollback(db -> {
                seedBase();
                assertViolation(() -> insertInvoice(UUID.randomUUID(), "2026-09-25", "2026-10-25", "2026-11-05", "2026-11"),
                        "uq_credit_card_invoices_card_closing");
            });
        }

        @Test
        @DisplayName("mesmo reference_month com fechamentos diferentes é aceito (S-13)")
        void deveAceitarReferenciaRepetida() {
            inRollback(db -> {
                seedBase();
                assertThatCode(() -> insertInvoice(UUID.randomUUID(), "2026-10-25", "2026-11-25", "2026-11-28", "2026-11"))
                        .doesNotThrowAnyException();
            });
        }

        @Test
        @DisplayName("period_start >= closing_date viola ck_credit_card_invoices_period")
        void deveRejeitarPeriodoInvertido() {
            inRollback(db -> {
                seedBase();
                assertViolation(() -> insertInvoice(UUID.randomUUID(), "2026-11-25", "2026-11-25", "2026-12-05", "2026-12"),
                        "ck_credit_card_invoices_period");
            });
        }

        @Test
        @DisplayName("mesmo (transaction_id, number) viola uq_installments_transaction_number")
        void deveRejeitarParcelaRepetida() {
            inRollback(db -> {
                seedBase();
                UUID purchase = insertCardPurchase(UUID.randomUUID(), 6);
                insertInstallment(purchase, 1, 20000);
                assertViolation(() -> insertInstallment(purchase, 1, 20000), "uq_installments_transaction_number");
            });
        }
    }

    @Nested
    @DisplayName("deduplicação (uq_transactions_user_dedup_active)")
    class Dedup {

        @Test
        @DisplayName("duas ACTIVE com a mesma chave violam o índice")
        void deveRejeitarChaveRepetidaEntreAtivas() {
            inRollback(db -> {
                seedBase();
                insertImported(UUID.randomUUID(), "ACTIVE", KEY);
                assertViolation(() -> insertImported(UUID.randomUUID(), "ACTIVE", KEY), "uq_transactions_user_dedup_active");
            });
        }

        @Test
        @DisplayName("com a primeira VOIDED, a segunda passa")
        void deveAceitarChaveDeEstornada() {
            inRollback(db -> {
                seedBase();
                insertImported(UUID.randomUUID(), "VOIDED", KEY);
                assertThatCode(() -> insertImported(UUID.randomUUID(), "ACTIVE", KEY)).doesNotThrowAnyException();
            });
        }

        @Test
        @DisplayName("duplicata forçada gravada com dedup_key nulo (S-43) e chave original guardada passa")
        void deveAceitarDuplicataForcadaComChaveNula() {
            inRollback(db -> {
                seedBase();
                insertImported(UUID.randomUUID(), "ACTIVE", KEY);
                assertThatCode(() -> insertImported(UUID.randomUUID(), "ACTIVE", null)).doesNotThrowAnyException();
                assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM transactions WHERE original_dedup_key = ?", Long.class, KEY)).isEqualTo(2L);
            });
        }
    }

    @Test
    @DisplayName("duas realizações ativas da mesma ocorrência violam uq_transactions_recurrence_occurrence")
    void deveRejeitarOcorrenciaRealizadaDuasVezes() {
        inRollback(db -> {
            seedBase();
            UUID recurrence = insertRecurrence();
            insertRealization(UUID.randomUUID(), recurrence, "ACTIVE");
            assertViolation(() -> insertRealization(UUID.randomUUID(), recurrence, "ACTIVE"),
                    "uq_transactions_recurrence_occurrence");
        });
    }

    @Test
    @DisplayName("a ocorrência cuja realização foi estornada pode ser realizada de novo (S-49)")
    void deveAceitarNovaRealizacaoDepoisDoEstorno() {
        inRollback(db -> {
            seedBase();
            UUID recurrence = insertRecurrence();
            insertRealization(UUID.randomUUID(), recurrence, "VOIDED");
            assertThatCode(() -> insertRealization(UUID.randomUUID(), recurrence, "ACTIVE")).doesNotThrowAnyException();
        });
    }

    @Nested
    @DisplayName("ledger_postings")
    class Postings {

        @Test
        @DisplayName("posting TRANSACTION sem transaction_id viola ck_ledger_postings_transaction_source")
        void deveExigirTransactionIdNaOrigemTransaction() {
            inRollback(db -> {
                seedBase();
                assertViolation(() -> insertPosting("TRANSACTION", UUID.randomUUID(), null, null, "ACCOUNT", ACCOUNT),
                        "ck_ledger_postings_transaction_source");
            });
        }

        @Test
        @DisplayName("posting ACCOUNT_OPENING com transaction_id viola ck_ledger_postings_transaction_source")
        void deveRejeitarTransactionIdEmOutraOrigem() {
            inRollback(db -> {
                seedBase();
                UUID tx = insertExpense(UUID.randomUUID(), 100, "ACTIVE", null);
                assertViolation(() -> insertPosting("ACCOUNT_OPENING", ACCOUNT, tx, null, "ACCOUNT", ACCOUNT),
                        "ck_ledger_postings_transaction_source");
            });
        }

        @Test
        @DisplayName("installment_id só existe com origem TRANSACTION")
        void deveRejeitarParcelaForaDeTransaction() {
            inRollback(db -> {
                seedBase();
                UUID purchase = insertCardPurchase(UUID.randomUUID(), 1);
                UUID installment = insertInstallment(purchase, 1, 100);
                assertViolation(() -> insertPosting("DEBT_RECOGNITION", UUID.randomUUID(), null, installment, "EQUITY", null),
                        "ck_ledger_postings_transaction_source");
            });
        }

        @Test
        @DisplayName("as quatro origens válidas são aceitas e outra é rejeitada (ck_ledger_postings_source_type)")
        void deveValidarOrigens() {
            inRollback(db -> {
                seedBase();
                UUID tx = insertExpense(UUID.randomUUID(), 100, "ACTIVE", null);
                insertPosting("TRANSACTION", tx, tx, null, "ACCOUNT", ACCOUNT);
                insertPosting("ACCOUNT_OPENING", ACCOUNT, null, null, "EQUITY", null);
                insertPosting("DEBT_RECOGNITION", UUID.randomUUID(), null, null, "EQUITY", null);
                insertPosting("DEBT_CANCELLATION", UUID.randomUUID(), null, null, "EQUITY", null);
                assertViolation(() -> insertPosting("INSTALLMENT", UUID.randomUUID(), null, null, "EQUITY", null),
                        "ck_ledger_postings_source_type");
            });
        }

        @Test
        @DisplayName("EQUITY não tem book_id; os demais livros exigem")
        void deveValidarBookId() {
            inRollback(db -> {
                seedBase();
                assertViolation(() -> insertPosting("ACCOUNT_OPENING", ACCOUNT, null, null, "ACCOUNT", null),
                        "ck_ledger_postings_book_id");
            });
        }

        @Test
        @DisplayName("posting inativo exige deactivated_at e motivo")
        void deveExigirDadosDaDesativacao() {
            inRollback(db -> {
                seedBase();
                assertViolation(() -> jdbc.update("""
                        INSERT INTO ledger_postings (id, user_id, source_type, source_id, book_type, book_id, competence,
                            effective_date, amount_cents, currency, active, created_at, updated_at)
                        VALUES (?, ?, 'ACCOUNT_OPENING', ?, 'ACCOUNT', ?, '2026-10', ?, 100, 'BRL', false, ?, ?)""",
                        UUID.randomUUID(), USER, ACCOUNT, ACCOUNT, DAY, NOW, NOW), "ck_ledger_postings_deactivation");
            });
        }
    }

    @Nested
    @DisplayName("debts")
    class Dividas {

        @Test
        @DisplayName("dívida e origem gravadas na mesma transação, dívida primeiro (FK diferida)")
        void deveAceitarDividaAntesDaOrigem() {
            inRollback(db -> {
                seedBase();
                UUID tx = UUID.randomUUID();
                UUID debt = UUID.randomUUID();
                insertDebt(debt, "WITH_MOVEMENT", tx);
                assertThatCode(() -> jdbc.update("""
                        INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date,
                            account_id, debt_id, source, created_at, updated_at)
                        VALUES (?, ?, 'LOAN_GIVEN', 'ACTIVE', 30000, 'BRL', ?, ?, ?, 'MANUAL', ?, ?)""",
                        tx, USER, DAY, ACCOUNT, debt, NOW, NOW)).doesNotThrowAnyException();
                assertThatCode(() -> jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE")).doesNotThrowAnyException();
            });
        }

        @Test
        @DisplayName("WITH_MOVEMENT sem origem e WITHOUT_MOVEMENT com origem violam ck_debts_origin_transaction")
        void deveValidarOrigemDaDivida() {
            inRollback(db -> {
                seedBase();
                assertViolation(() -> insertDebt(UUID.randomUUID(), "WITH_MOVEMENT", null), "ck_debts_origin_transaction");
            });
            inRollback(db -> {
                seedBase();
                assertViolation(() -> insertDebt(UUID.randomUUID(), "WITHOUT_MOVEMENT", UUID.randomUUID()),
                        "ck_debts_origin_transaction");
            });
        }

        @Test
        @DisplayName("duas dívidas podem ter a mesma origem: a despesa dividida tem uma dívida por parte (V4.01)")
        void deveAceitarOrigemCompartilhada() {
            inRollback(db -> {
                seedBase();
                UUID tx = UUID.randomUUID();
                insertDebt(UUID.randomUUID(), "WITH_MOVEMENT", tx);
                assertThatCode(() -> insertDebt(UUID.randomUUID(), "WITH_MOVEMENT", tx)).doesNotThrowAnyException();
            });
        }

        @Test
        @DisplayName("closure só aceita nulo, CANCELED ou VOIDED (ck_debts_closure)")
        void deveValidarClosure() {
            inRollback(db -> {
                seedBase();
                assertViolation(() -> jdbc.update("""
                        INSERT INTO debts (id, user_id, person_id, direction, origin, principal_cents, currency, debt_date,
                            closure, closure_date, closure_reason, created_at, updated_at)
                        VALUES (?, ?, ?, 'PAYABLE', 'WITHOUT_MOVEMENT', 20000, 'BRL', ?, 'SETTLED', ?, 'x', ?, ?)""",
                        UUID.randomUUID(), USER, PERSON, DAY, DAY, NOW, NOW), "ck_debts_closure");
            });
        }
    }

    @Test
    @DisplayName("ck_transactions_type tem exatamente os 11 tipos do PR 01")
    void deveListarOsOnzeTipos() {
        String def = constraintDef("ck_transactions_type");
        for (String type : List.of("EXPENSE", "INCOME", "TRANSFER", "INVESTMENT", "LOAN_GIVEN", "MONEY_RECEIVED",
                "DEBT_PAYMENT", "CARD_PURCHASE", "INVOICE_PAYMENT", "REFUND", "ADJUSTMENT")) {
            assertThat(def).contains("'" + type + "'");
        }
        assertThat(def.split("'::").length - 1).isEqualTo(11);
    }

    @ParameterizedTest(name = "closing_day = {0}")
    @ValueSource(ints = {0, 32})
    @DisplayName("closing_day da regra fora de 1..31 é rejeitado")
    void deveRejeitarDiaDeFechamentoInvalido(int day) {
        inRollback(db -> {
            seedBase();
            assertViolation(() -> insertBillingRule(USER, "2027-01", day, 5), "ck_credit_card_billing_rules_closing_day");
        });
    }

    @ParameterizedTest(name = "due_day = {0}")
    @ValueSource(ints = {0, 32})
    @DisplayName("due_day da regra fora de 1..31 é rejeitado")
    void deveRejeitarDiaDeVencimentoInvalido(int day) {
        inRollback(db -> {
            seedBase();
            assertViolation(() -> insertBillingRule(USER, "2027-01", 25, day), "ck_credit_card_billing_rules_due_day");
        });
    }

    @Test
    @DisplayName("M4: credit_cards não tem closing_day/due_day; a regra (fonte única) fica em credit_card_billing_rules")
    void naoDeveDuplicarARegraNoCartao() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'credit_cards'", String.class);
        assertThat(columns).doesNotContain("closing_day", "due_day");
    }

    @Test
    @DisplayName("M4: a regra de fechamento é sempre do dono do cartão (FK composta card_id, user_id)")
    void deveAmarrarARegraAoDonoDoCartao() {
        inRollback(db -> {
            seedBase();
            UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
            jdbc.update("INSERT INTO users (id, email, created_at, updated_at) VALUES (?, 'other@moneylog.test', ?, ?)", other, NOW, NOW);
            assertViolation(() -> insertBillingRule(other, "2027-01", 25, 5), "fk_credit_card_billing_rules_card");
            assertThatCode(() -> insertBillingRule(USER, "2027-01", 10, 20)).doesNotThrowAnyException();
        });
    }

    @ParameterizedTest(name = "installments = {0}")
    @ValueSource(ints = {0, 37})
    @DisplayName("installments fora de 1..36 é rejeitado")
    void deveRejeitarQuantidadeDeParcelasInvalida(int installments) {
        inRollback(db -> {
            seedBase();
            assertViolation(() -> insertCardPurchase(UUID.randomUUID(), installments), "ck_transactions_installments");
        });
    }

    @Test
    @DisplayName("nenhuma migração apaga tabela, coluna ou linha (só DROP CONSTRAINT para trocar FKs, V2.00)")
    void naoDeveApagarDados() throws Exception {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath:db/migration/*.sql");
        assertThat(resources).isNotEmpty();
        for (var resource : resources) {
            String sql = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("--[^\n]*", "").toUpperCase()
                    .replace("ON DELETE CASCADE", "");
            assertThat(sql).as(resource.getFilename()).doesNotContain("DROP TABLE").doesNotContain("DROP COLUMN")
                    .doesNotContain("DROP SCHEMA").doesNotContain("TRUNCATE").doesNotContain("DELETE ");
        }
    }

    private void inRollback(Consumer<JdbcTemplate> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            status.setRollbackOnly();
            work.accept(jdbc);
        });
    }

    private void assertViolation(Runnable insert, String constraint) {
        jdbc.execute("SAVEPOINT before_violation");
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
        jdbc.execute("ROLLBACK TO SAVEPOINT before_violation");
    }

    private String indexDef(String name) {
        return jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                String.class, name);
    }

    private String constraintDef(String name) {
        return jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, name);
    }

    private boolean hasColumn(String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT count(*) > 0 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? "
                + "AND column_name = ?", Boolean.class, table, column));
    }

    private String column(String table, String column) {
        return jdbc.queryForObject("""
                SELECT data_type || '|' || is_nullable || '|' || coalesce(column_default, 'null')
                FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? AND column_name = ?""",
                String.class, table, column);
    }

    private List<String> columnNames(String table) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
                String.class, table);
    }

    private void seedUser() {
        jdbc.update("INSERT INTO users (id, email, created_at, updated_at) VALUES (?, 'owner@moneylog.test', ?, ?)", USER, NOW, NOW);
    }

    private void seedBase() {
        seedUser();
        jdbc.update("""
                INSERT INTO accounts (id, user_id, name, type, opening_balance_cents, currency, opening_date, status, created_at, updated_at)
                VALUES (?, ?, 'Nubank', 'CHECKING', 2000000, 'BRL', ?, 'ACTIVE', ?, ?)""", ACCOUNT, USER, DAY, NOW, NOW);
        jdbc.update("""
                INSERT INTO categories (id, user_id, name, kind, system, created_at, updated_at)
                VALUES (?, ?, 'Alimentação', 'EXPENSE', true, ?, ?)""", CATEGORY, USER, NOW, NOW);
        jdbc.update("""
                INSERT INTO credit_cards (id, user_id, name, limit_cents, currency, status, created_at, updated_at)
                VALUES (?, ?, 'Roxinho', 1000000, 'BRL', 'ACTIVE', ?, ?)""", CARD, USER, NOW, NOW);
        insertBillingRule(USER, "2026-09", 25, 5);
        insertInvoice(INVOICE, "2026-09-25", "2026-10-25", "2026-11-05", "2026-11");
        jdbc.update("INSERT INTO persons (id, user_id, name, created_at, updated_at) VALUES (?, ?, 'João', ?, ?)", PERSON, USER, NOW, NOW);
    }

    private void insertBillingRule(UUID userId, String from, int closingDay, int dueDay) {
        jdbc.update("""
                INSERT INTO credit_card_billing_rules (id, user_id, card_id, effective_from, closing_day, due_day, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", UUID.nameUUIDFromBytes((CARD + from + userId).getBytes()), userId, CARD, from,
                closingDay, dueDay, NOW, NOW);
    }

    private void insertInvoice(UUID id, String start, String closing, String due, String reference) {
        jdbc.update("""
                INSERT INTO credit_card_invoices (id, user_id, card_id, period_start, closing_date, due_date, reference_month, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, USER, CARD, Date.valueOf(start), Date.valueOf(closing),
                Date.valueOf(due), reference, NOW, NOW);
    }

    private UUID insertExpense(UUID id, long cents, String status, String dedupKey) {
        boolean voided = status.equals("VOIDED");
        jdbc.update("""
                INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, payment_method,
                    account_id, category_id, source, dedup_key, void_reason, voided_at, created_at, updated_at)
                VALUES (?, ?, 'EXPENSE', ?, ?, 'BRL', ?, 'PIX', ?, ?, 'MANUAL', ?, ?, ?, ?, ?)""",
                id, USER, status, cents, DAY, ACCOUNT, CATEGORY, dedupKey, voided ? "motivo" : null, voided ? NOW : null, NOW, NOW);
        return id;
    }

    private UUID insertImported(UUID id, String status, String dedupKey) {
        boolean voided = status.equals("VOIDED");
        UUID batch = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO import_batches (id, user_id, account_id, format, file_sha256, status, created_at, updated_at)
                VALUES (?, ?, ?, 'CSV', ?, 'APPLIED', ?, ?)""", batch, USER, ACCOUNT, KEY, NOW, NOW);
        jdbc.update("""
                INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, payment_method,
                    account_id, category_id, source, import_batch_id, dedup_key, original_dedup_key, void_reason, voided_at,
                    created_at, updated_at)
                VALUES (?, ?, 'EXPENSE', ?, 850, 'BRL', ?, 'DEBIT', ?, ?, 'IMPORT', ?, ?, ?, ?, ?, ?, ?)""",
                id, USER, status, DAY, ACCOUNT, CATEGORY, batch, dedupKey, KEY, voided ? "motivo" : null,
                voided ? NOW : null, NOW, NOW);
        return id;
    }

    private UUID insertRecurrence() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recurring_transactions (id, user_id, type, amount_cents, currency, description, category_id, account_id,
                    payment_method, frequency, interval_count, start_date, active, created_at, updated_at)
                VALUES (?, ?, 'EXPENSE', 11900, 'BRL', 'Internet', ?, ?, 'BOLETO', 'MONTHLY', 1, ?, TRUE, ?, ?)""",
                id, USER, CATEGORY, ACCOUNT, DAY, NOW, NOW);
        return id;
    }

    private UUID insertRealization(UUID id, UUID recurrenceId, String status) {
        boolean voided = status.equals("VOIDED");
        jdbc.update("""
                INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, payment_method,
                    account_id, category_id, source, recurrence_id, occurrence_date, void_reason, voided_at, created_at, updated_at)
                VALUES (?, ?, 'EXPENSE', ?, 11900, 'BRL', ?, 'BOLETO', ?, ?, 'RECURRENCE', ?, ?, ?, ?, ?, ?)""",
                id, USER, status, DAY, ACCOUNT, CATEGORY, recurrenceId, DAY, voided ? "motivo" : null, voided ? NOW : null, NOW, NOW);
        return id;
    }

    private UUID insertCardPurchase(UUID id, int installments) {
        jdbc.update("""
                INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, card_id,
                    category_id, installments, source, created_at, updated_at)
                VALUES (?, ?, 'CARD_PURCHASE', 'ACTIVE', 120000, 'BRL', ?, ?, ?, ?, 'MANUAL', ?, ?)""",
                id, USER, DAY, CARD, CATEGORY, installments, NOW, NOW);
        return id;
    }

    private UUID insertInstallment(UUID transactionId, int number, long cents) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transaction_installments (id, user_id, transaction_id, number, total_installments, amount_cents,
                    currency, competence, effective_date, invoice_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 6, ?, 'BRL', '2026-10', ?, ?, ?, ?)""",
                id, USER, transactionId, number, cents, DAY, INVOICE, NOW, NOW);
        return id;
    }

    private void insertPosting(String sourceType, UUID sourceId, UUID transactionId, UUID installmentId, String bookType, UUID bookId) {
        jdbc.update("""
                INSERT INTO ledger_postings (id, user_id, source_type, source_id, transaction_id, installment_id, book_type,
                    book_id, competence, effective_date, amount_cents, currency, active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '2026-10', ?, 100, 'BRL', true, ?, ?)""",
                UUID.randomUUID(), USER, sourceType, sourceId, transactionId, installmentId, bookType, bookId, DAY, NOW, NOW);
    }

    private void insertDebt(UUID id, String origin, UUID originTransactionId) {
        jdbc.update("""
                INSERT INTO debts (id, user_id, person_id, direction, origin, principal_cents, currency, debt_date,
                    origin_transaction_id, created_at, updated_at)
                VALUES (?, ?, ?, 'RECEIVABLE', ?, 30000, 'BRL', ?, ?, ?, ?)""",
                id, USER, PERSON, origin, DAY, originTransactionId, NOW, NOW);
    }
}
