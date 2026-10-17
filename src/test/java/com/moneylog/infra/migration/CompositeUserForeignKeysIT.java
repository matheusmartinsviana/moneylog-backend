package com.moneylog.infra.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.util.IntegrationTest;

@IntegrationTest
@DisplayName("FKs compostas (id, user_id) (T46, S-76)")
class CompositeUserForeignKeysIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));
    private static final Date DAY = Date.valueOf(LocalDate.of(2026, 10, 10));

    private static final List<String> PARENT_UNIQUES = List.of(
            "uq_accounts_id_user", "uq_categories_id_user", "uq_credit_cards_id_user", "uq_credit_card_invoices_id_user",
            "uq_persons_id_user", "uq_debts_id_user", "uq_transactions_id_user", "uq_transaction_installments_id_user");

    private static final List<String[]> COMPOSITE_FKS = List.of(
            new String[] {"fk_credit_cards_default_payment_account_id_user", "FOREIGN KEY (default_payment_account_id, user_id) REFERENCES accounts(id, user_id)"},
            new String[] {"fk_credit_card_invoices_card_id_user", "FOREIGN KEY (card_id, user_id) REFERENCES credit_cards(id, user_id)"},
            new String[] {"fk_debts_person_id_user", "FOREIGN KEY (person_id, user_id) REFERENCES persons(id, user_id)"},
            new String[] {"fk_debts_origin_transaction_id_user", "FOREIGN KEY (origin_transaction_id, user_id) REFERENCES transactions(id, user_id) DEFERRABLE INITIALLY DEFERRED"},
            new String[] {"fk_transactions_account_id_user", "FOREIGN KEY (account_id, user_id) REFERENCES accounts(id, user_id)"},
            new String[] {"fk_transactions_counterpart_account_id_user", "FOREIGN KEY (counterpart_account_id, user_id) REFERENCES accounts(id, user_id)"},
            new String[] {"fk_transactions_card_id_user", "FOREIGN KEY (card_id, user_id) REFERENCES credit_cards(id, user_id)"},
            new String[] {"fk_transactions_category_id_user", "FOREIGN KEY (category_id, user_id) REFERENCES categories(id, user_id)"},
            new String[] {"fk_transactions_invoice_id_user", "FOREIGN KEY (invoice_id, user_id) REFERENCES credit_card_invoices(id, user_id)"},
            new String[] {"fk_transactions_debt_id_user", "FOREIGN KEY (debt_id, user_id) REFERENCES debts(id, user_id)"},
            new String[] {"fk_transactions_refund_of_id_user", "FOREIGN KEY (refund_of_id, user_id) REFERENCES transactions(id, user_id)"},
            new String[] {"fk_transaction_installments_transaction_id_user", "FOREIGN KEY (transaction_id, user_id) REFERENCES transactions(id, user_id)"},
            new String[] {"fk_transaction_installments_invoice_id_user", "FOREIGN KEY (invoice_id, user_id) REFERENCES credit_card_invoices(id, user_id)"},
            new String[] {"fk_transactions_import_batch_id_user", "FOREIGN KEY (import_batch_id, user_id) REFERENCES import_batches(id, user_id)"},
            new String[] {"fk_ledger_postings_transaction_id_user", "FOREIGN KEY (transaction_id, user_id) REFERENCES transactions(id, user_id)"},
            new String[] {"fk_ledger_postings_installment_id_user", "FOREIGN KEY (installment_id, user_id) REFERENCES transaction_installments(id, user_id)"});

    private static final List<String> REPLACED_FKS = List.of(
            "fk_credit_cards_default_account", "fk_credit_card_invoices_card", "fk_debts_person", "fk_debts_origin_transaction",
            "fk_transactions_account", "fk_transactions_counterpart_account", "fk_transactions_card", "fk_transactions_category",
            "fk_transactions_invoice", "fk_transactions_debt", "fk_transactions_refund_of", "fk_installments_transaction",
            "fk_installments_invoice", "fk_transactions_import_batch", "fk_ledger_postings_transaction", "fk_ledger_postings_installment");

    private static final long A = 1;
    private static final long B = 2;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("CA1: cada tabela pai tem uq_<tabela>_id_user em (id, user_id)")
    void deveTerUniqueIdUserNasTabelasPai() {
        for (String name : PARENT_UNIQUES) {
            assertThat(constraintDef(name)).as(name).isEqualTo("UNIQUE (id, user_id)");
        }
        assertThat(PARENT_UNIQUES).hasSize(8);
    }

    @Test
    @DisplayName("CA2/CA4: as FKs compostas existem com o nome exato e a definição esperada; a da origem da dívida segue DEFERRABLE INITIALLY DEFERRED")
    void deveTerAsFksCompostasComNomeExato() {
        for (String[] fk : COMPOSITE_FKS) {
            assertThat(constraintDef(fk[0])).as(fk[0]).isEqualTo(fk[1]);
        }
        assertThat(COMPOSITE_FKS).hasSize(16);
    }

    @Test
    @DisplayName("CA4: as FKs simples substituídas não existem mais e nenhuma FK de uma coluna só aponta para tabela de negócio")
    void deveTerSubstituidoAsFksSimples() {
        List<String> names = jdbc.queryForList(
                "SELECT conname FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = 'public'",
                String.class);
        assertThat(names).doesNotContainAnyElementsOf(REPLACED_FKS);
        List<String> simples = jdbc.queryForList("""
                SELECT conname || ' -> ' || confrelid::regclass::text FROM pg_constraint
                WHERE contype = 'f' AND array_length(conkey, 1) = 1 AND confrelid::regclass::text <> 'users'""", String.class);
        assertThat(simples).as("só as tabelas fiscais globais (sem usuário) têm FK simples").containsExactlyInAnyOrder(
                "fk_tax_table_brackets_table -> tax_tables", "fk_tax_table_sources_table -> tax_tables");
        assertThat(constraintDef("fk_credit_card_billing_rules_card"))
                .isEqualTo("FOREIGN KEY (card_id, user_id) REFERENCES credit_cards(id, user_id)");
    }

    @Test
    @DisplayName("ledger_postings.book_id (polimórfico) continua sem FK")
    void naoDeveTerFkNoBookId() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                WHERE c.contype = 'f' AND c.conrelid = 'ledger_postings'::regclass AND a.attname = 'book_id'""", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("CA4: a V2.00 não apaga tabela, coluna nem linha")
    void naoDeveApagarDadosNaV200() throws Exception {
        var resource = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V2.00__add_composite_user_foreign_keys.sql");
        String sql = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("--[^\n]*", "").toUpperCase();
        assertThat(sql).doesNotContain("DROP TABLE").doesNotContain("DROP COLUMN").doesNotContain("DELETE ")
                .doesNotContain("TRUNCATE");
    }

    @Test
    @DisplayName("CA3: movimentação do usuário A apontando para conta, contrapartida, cartão, categoria, fatura, dívida ou original do usuário B é recusada")
    void deveRecusarMovimentacaoApontandoParaOutroUsuario() {
        inRollback(() -> {
            seed(A);
            seed(B);
            assertViolation(() -> insertTransaction(A, id(A, 100), id(B, 1), null, null, null, null, null, null),
                    "fk_transactions_account_id_user");
            assertViolation(() -> insertTransaction(A, id(A, 100), id(A, 1), id(B, 2), null, null, null, null, null),
                    "fk_transactions_counterpart_account_id_user");
            assertViolation(() -> insertTransaction(A, id(A, 100), id(A, 1), null, id(B, 4), null, null, null, null),
                    "fk_transactions_card_id_user");
            assertViolation(() -> insertTransaction(A, id(A, 100), id(A, 1), null, null, id(B, 3), null, null, null),
                    "fk_transactions_category_id_user");
            assertViolation(() -> insertTransaction(A, id(A, 100), id(A, 1), null, null, null, id(B, 5), null, null),
                    "fk_transactions_invoice_id_user");
            assertViolation(() -> insertTransaction(A, id(A, 100), id(A, 1), null, null, null, null, id(B, 10), null),
                    "fk_transactions_debt_id_user");
            assertViolation(() -> insertTransaction(A, id(A, 100), id(A, 1), null, null, null, null, null, id(B, 7)),
                    "fk_transactions_refund_of_id_user");
        });
    }

    @Test
    @DisplayName("CA3 (controle): as mesmas referências para registros do próprio usuário, ou ausentes, são aceitas")
    void deveAceitarReferenciasDoProprioUsuario() {
        inRollback(() -> {
            seed(A);
            seed(B);
            assertThatCode(() -> {
                insertTransaction(A, id(A, 100), id(A, 1), id(A, 2), id(A, 4), id(A, 3), id(A, 5), id(A, 10), id(A, 7));
                insertTransaction(B, id(B, 100), id(B, 1), null, null, id(B, 3), null, null, id(B, 7));
                insertTransaction(A, id(A, 101), null, null, null, null, null, null, null);
            }).doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("CA3: cartão com conta padrão do outro usuário, fatura de cartão do outro usuário e dívida com pessoa do outro usuário são recusados")
    void deveRecusarCadastrosApontandoParaOutroUsuario() {
        inRollback(() -> {
            seed(A);
            seed(B);
            assertViolation(() -> jdbc.update("""
                    INSERT INTO credit_cards (id, user_id, name, limit_cents, currency, default_payment_account_id, status, created_at, updated_at)
                    VALUES (?, ?, 'Cartão', 100000, 'BRL', ?, 'ACTIVE', ?, ?)""", id(A, 50), uid(A), id(B, 1), NOW, NOW),
                    "fk_credit_cards_default_payment_account_id_user");
            assertViolation(() -> insertInvoice(A, id(A, 51), id(B, 4), "2026-10-26"),
                    "fk_credit_card_invoices_card_id_user");
            assertViolation(() -> insertDebt(A, id(A, 52), id(B, 6), null), "fk_debts_person_id_user");
            assertThatCode(() -> insertDebt(A, id(A, 53), id(A, 6), null)).doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("CA3: a origem da dívida é de outro usuário: recusada na hora do commit (FK diferida)")
    void deveRecusarOrigemDeDividaDeOutroUsuario() {
        inRollback(() -> {
            seed(A);
            seed(B);
            jdbc.execute("SET CONSTRAINTS fk_debts_origin_transaction_id_user IMMEDIATE");
            assertViolation(() -> jdbc.update("""
                    INSERT INTO debts (id, user_id, person_id, direction, origin, principal_cents, currency, debt_date,
                        origin_transaction_id, created_at, updated_at)
                    VALUES (?, ?, ?, 'RECEIVABLE', 'WITH_MOVEMENT', 30000, 'BRL', ?, ?, ?, ?)""",
                    id(A, 54), uid(A), id(A, 6), DAY, id(B, 7), NOW, NOW), "fk_debts_origin_transaction_id_user");
            assertThatCode(() -> jdbc.update("""
                    INSERT INTO debts (id, user_id, person_id, direction, origin, principal_cents, currency, debt_date,
                        origin_transaction_id, created_at, updated_at)
                    VALUES (?, ?, ?, 'RECEIVABLE', 'WITH_MOVEMENT', 30000, 'BRL', ?, ?, ?, ?)""",
                    id(A, 55), uid(A), id(A, 6), DAY, id(A, 7), NOW, NOW)).doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("CA3: parcela apontando para movimentação ou fatura de outro usuário é recusada")
    void deveRecusarParcelaApontandoParaOutroUsuario() {
        inRollback(() -> {
            seed(A);
            seed(B);
            assertViolation(() -> insertInstallment(A, id(A, 60), id(B, 8), id(A, 5), 2),
                    "fk_transaction_installments_transaction_id_user");
            assertViolation(() -> insertInstallment(A, id(A, 61), id(A, 8), id(B, 5), 2),
                    "fk_transaction_installments_invoice_id_user");
            assertThatCode(() -> insertInstallment(A, id(A, 62), id(A, 8), id(A, 5), 2)).doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("CA3: posting apontando para movimentação ou parcela de outro usuário é recusado")
    void deveRecusarPostingApontandoParaOutroUsuario() {
        inRollback(() -> {
            seed(A);
            seed(B);
            assertViolation(() -> insertPosting(A, id(B, 7), id(B, 7), null), "fk_ledger_postings_transaction_id_user");
            assertViolation(() -> insertPosting(A, id(A, 7), id(A, 7), id(B, 9)), "fk_ledger_postings_installment_id_user");
            assertThatCode(() -> insertPosting(A, id(A, 8), id(A, 8), id(A, 9))).doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("BUG-QA-10: movimentação importada do usuário A apontando para lote do usuário B é recusada; para o próprio lote é aceita")
    void deveRecusarMovimentacaoComLoteDeOutroUsuario() {
        inRollback(() -> {
            seed(A);
            seed(B);
            insertBatch(A, id(A, 200));
            insertBatch(B, id(B, 200));
            assertViolation(() -> insertImported(A, id(A, 201), id(B, 200)), "fk_transactions_import_batch_id_user");
            assertThatCode(() -> insertImported(A, id(A, 202), id(A, 200))).doesNotThrowAnyException();
        });
    }

    private void insertBatch(long user, UUID id) {
        jdbc.update("""
                INSERT INTO import_batches (id, user_id, account_id, format, file_sha256, status, created_at, updated_at)
                VALUES (?, ?, ?, 'CSV', ?, 'APPLIED', ?, ?)""", id, uid(user), this.id(user, 1), "a".repeat(64), NOW, NOW);
    }

    private void insertImported(long user, UUID id, UUID batchId) {
        jdbc.update("""
                INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, payment_method,
                    account_id, source, import_batch_id, created_at, updated_at)
                VALUES (?, ?, 'EXPENSE', 'ACTIVE', 10000, 'BRL', ?, 'PIX', ?, 'IMPORT', ?, ?, ?)""",
                id, uid(user), DAY, this.id(user, 1), batchId, NOW, NOW);
    }

    private static UUID id(long user, long n) {
        return new UUID(user, n);
    }

    private static UUID uid(long user) {
        return id(user, 0);
    }

    private void inRollback(Runnable work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            status.setRollbackOnly();
            work.run();
        });
    }

    private void assertViolation(Runnable insert, String constraint) {
        jdbc.execute("SAVEPOINT before_violation");
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
        jdbc.execute("ROLLBACK TO SAVEPOINT before_violation");
    }

    private String constraintDef(String name) {
        return jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, name);
    }

    private void seed(long user) {
        jdbc.update("INSERT INTO users (id, email, created_at, updated_at) VALUES (?, ?, ?, ?)", uid(user),
                "owner" + user + "@moneylog.test", NOW, NOW);
        for (long account : new long[] {1, 2}) {
            jdbc.update("""
                    INSERT INTO accounts (id, user_id, name, type, opening_balance_cents, currency, opening_date, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'CHECKING', 0, 'BRL', ?, 'ACTIVE', ?, ?)""", id(user, account), uid(user), "Conta " + account, DAY, NOW, NOW);
        }
        jdbc.update("""
                INSERT INTO categories (id, user_id, name, kind, system, created_at, updated_at)
                VALUES (?, ?, 'Alimentação', 'EXPENSE', true, ?, ?)""", id(user, 3), uid(user), NOW, NOW);
        jdbc.update("""
                INSERT INTO credit_cards (id, user_id, name, limit_cents, currency, status, created_at, updated_at)
                VALUES (?, ?, 'Roxinho', 1000000, 'BRL', 'ACTIVE', ?, ?)""", id(user, 4), uid(user), NOW, NOW);
        insertInvoice(user, id(user, 5), id(user, 4), "2026-10-25");
        jdbc.update("INSERT INTO persons (id, user_id, name, created_at, updated_at) VALUES (?, ?, 'João', ?, ?)", id(user, 6), uid(user), NOW, NOW);
        insertTransaction(user, id(user, 7), id(user, 1), null, null, id(user, 3), null, null, null);
        jdbc.update("""
                INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, card_id, category_id,
                    installments, source, created_at, updated_at)
                VALUES (?, ?, 'CARD_PURCHASE', 'ACTIVE', 120000, 'BRL', ?, ?, ?, 6, 'MANUAL', ?, ?)""",
                id(user, 8), uid(user), DAY, id(user, 4), id(user, 3), NOW, NOW);
        insertInstallment(user, id(user, 9), id(user, 8), id(user, 5), 1);
        insertDebt(user, id(user, 10), id(user, 6), null);
    }

    private void insertInvoice(long user, UUID id, UUID cardId, String closing) {
        jdbc.update("""
                INSERT INTO credit_card_invoices (id, user_id, card_id, period_start, closing_date, due_date, reference_month, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, '2026-10', ?, ?)""", id, uid(user), cardId, Date.valueOf("2026-09-25"), Date.valueOf(closing),
                Date.valueOf("2026-11-05"), NOW, NOW);
    }

    private void insertTransaction(long user, UUID id, UUID accountId, UUID counterpartId, UUID cardId, UUID categoryId,
                                   UUID invoiceId, UUID debtId, UUID refundOfId) {
        jdbc.update("""
                INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, payment_method,
                    account_id, counterpart_account_id, card_id, category_id, invoice_id, debt_id, refund_of_id, source,
                    created_at, updated_at)
                VALUES (?, ?, 'EXPENSE', 'ACTIVE', 10000, 'BRL', ?, 'PIX', ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?)""",
                id, uid(user), DAY, accountId, counterpartId, cardId, categoryId, invoiceId, debtId, refundOfId, NOW, NOW);
    }

    private void insertInstallment(long user, UUID id, UUID transactionId, UUID invoiceId, int number) {
        jdbc.update("""
                INSERT INTO transaction_installments (id, user_id, transaction_id, number, total_installments, amount_cents,
                    currency, competence, effective_date, invoice_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 6, 20000, 'BRL', '2026-10', ?, ?, ?, ?)""", id, uid(user), transactionId, number, DAY, invoiceId, NOW, NOW);
    }

    private void insertDebt(long user, UUID id, UUID personId, UUID originTransactionId) {
        jdbc.update("""
                INSERT INTO debts (id, user_id, person_id, direction, origin, principal_cents, currency, debt_date,
                    origin_transaction_id, created_at, updated_at)
                VALUES (?, ?, ?, 'RECEIVABLE', 'WITHOUT_MOVEMENT', 30000, 'BRL', ?, ?, ?, ?)""",
                id, uid(user), personId, DAY, originTransactionId, NOW, NOW);
    }

    private void insertPosting(long user, UUID sourceId, UUID transactionId, UUID installmentId) {
        jdbc.update("""
                INSERT INTO ledger_postings (id, user_id, source_type, source_id, transaction_id, installment_id, book_type,
                    book_id, competence, effective_date, amount_cents, currency, active, created_at, updated_at)
                VALUES (?, ?, 'TRANSACTION', ?, ?, ?, 'EXPENSE', ?, '2026-10', ?, 100, 'BRL', true, ?, ?)""",
                UUID.randomUUID(), uid(user), sourceId, transactionId, installmentId, id(user, 3), DAY, NOW, NOW);
    }
}
