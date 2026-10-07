package com.moneylog.infra.ledger;

import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.finance.app.gateways.LedgerReadGateway;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.SqlFixtures;

/** T24: o razão gravado em {@code ledger_postings} e lido de volta (CA1 a CA7). */
@IntegrationTest
@Transactional
@DisplayName("Persistência do razão (T24)")
class LedgerPersistenceIT {

    private static final LocalDate OPENING = LocalDate.of(2026, 10, 1);
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    @Autowired
    private LedgerWriteGateway write;
    @Autowired
    private LedgerReadGateway read;
    @Autowired
    private JdbcTemplate jdbc;

    private final Account nubank = Account.open(UUID.fromString("00000000-0000-0000-0000-00000000a001"), USER_ID, "Nubank",
            AccountType.CHECKING, "Nubank", Money.of("1000.00"), OPENING);
    private final Category alimentacao = Category.createSystem(UUID.fromString("00000000-0000-0000-0000-00000000c001"), USER_ID,
            "Alimentação", CategoryKind.EXPENSE);

    @BeforeEach
    void inserirCadastros() {
        SqlFixtures.insertUser(jdbc, USER_ID, "ledger@moneylog.com");
        SqlFixtures.insertUser(jdbc, OTHER_USER_ID, "outro@moneylog.com");
        SqlFixtures.insertAccount(jdbc, nubank);
        SqlFixtures.insertCategory(jdbc, alimentacao);
    }

    private Transaction expense(String id, LocalDate date, String amount) {
        return AccountTransactions.expense(TransactionHeader.of(UUID.fromString(id), USER_ID, date, "Despesa"), Money.of(amount),
                nubank, alimentacao, PaymentMethod.PIX);
    }

    private void persist(Transaction transaction) {
        SqlFixtures.insertTransactionRow(jdbc, transaction);
        write.append(USER_ID, transaction.entries());
    }

    private void persistOpening() {
        write.append(USER_ID, LedgerEntry.activeGroup(PostingSource.accountOpening(nubank.id()), nubank.openingPostings()));
    }

    private Ledger ledgerOf(UUID userId) {
        return new Ledger(read.activeEntries(userId).values().stream().flatMap(List::stream).toList());
    }

    @Test
    @DisplayName("CA1: grava origem, livro, competência, data, centavos e transaction_id (só na despesa), somando zero por origem")
    void deveGravarOsLancamentos() {
        persistOpening();
        Transaction expense = expense("00000000-0000-0000-0000-0000000e0001", LocalDate.of(2026, 10, 10), "100.00");
        persist(expense);

        List<Map<String, Object>> opening = jdbc.queryForList(
                "SELECT * FROM ledger_postings WHERE source_type = 'ACCOUNT_OPENING' ORDER BY amount_cents");
        assertThat(opening).hasSize(2);
        assertThat(opening).allSatisfy(row -> {
            assertThat(row.get("transaction_id")).isNull();
            assertThat(row.get("source_id")).isEqualTo(nubank.id());
            assertThat(row.get("active")).isEqualTo(true);
            assertThat(row.get("competence")).isEqualTo("2026-10");
        });
        assertThat(opening.get(0)).containsEntry("book_type", "EQUITY").containsEntry("book_id", null)
                .containsEntry("amount_cents", -100_000L);
        assertThat(opening.get(1)).containsEntry("book_type", "ACCOUNT").containsEntry("book_id", nubank.id())
                .containsEntry("amount_cents", 100_000L);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM ledger_postings WHERE source_type = 'TRANSACTION' ORDER BY amount_cents");
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("transaction_id")).isEqualTo(expense.id());
            assertThat(row.get("source_id")).isEqualTo(expense.id());
            assertThat(row.get("effective_date")).isEqualTo(Date.valueOf(LocalDate.of(2026, 10, 10)));
            assertThat(row.get("currency")).isEqualTo("BRL");
        });
        assertThat(rows.get(0)).containsEntry("book_type", "ACCOUNT").containsEntry("amount_cents", -10_000L);
        assertThat(rows.get(1)).containsEntry("book_type", "EXPENSE").containsEntry("book_id", alimentacao.id())
                .containsEntry("amount_cents", 10_000L);
        assertThat(jdbc.queryForList("SELECT sum(amount_cents) FROM ledger_postings GROUP BY source_type, source_id", Long.class))
                .containsOnly(0L);
    }

    @Test
    @DisplayName("CA2: desativar por origem só afeta aquela origem, numa instrução, com data e motivo")
    void deveDesativarPorOrigem() {
        persistOpening();
        Transaction first = expense("00000000-0000-0000-0000-0000000e0001", LocalDate.of(2026, 10, 10), "100.00");
        Transaction second = expense("00000000-0000-0000-0000-0000000e0002", LocalDate.of(2026, 10, 20), "50.00");
        persist(first);
        persist(second);

        int affected = write.deactivateBySource(USER_ID, PostingSourceType.TRANSACTION, first.id(), "VOID", NOW);

        assertThat(affected).isEqualTo(2);
        List<Map<String, Object>> inactive = jdbc.queryForList("SELECT * FROM ledger_postings WHERE NOT active");
        assertThat(inactive).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("source_id")).isEqualTo(first.id());
            assertThat(row.get("deactivation_reason")).isEqualTo("VOID");
            assertThat(((java.sql.Timestamp) row.get("deactivated_at")).toInstant()).isEqualTo(NOW);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE active", Integer.class)).isEqualTo(4);
        // repetir não desativa de novo
        assertThat(write.deactivateBySource(USER_ID, PostingSourceType.TRANSACTION, first.id(), "VOID", NOW)).isZero();
    }

    @Test
    @DisplayName("CA3: saldo da Nubank lido do banco: 900 em 15/10, 850 em 20/10 e 950 em 20/10 sem a despesa de 10/10")
    void deveCalcularSaldoComOsLancamentosLidos() {
        persistOpening();
        Transaction first = expense("00000000-0000-0000-0000-0000000e0001", LocalDate.of(2026, 10, 10), "100.00");
        persist(first);
        persist(expense("00000000-0000-0000-0000-0000000e0002", LocalDate.of(2026, 10, 20), "50.00"));

        Ledger ledger = ledgerOf(USER_ID);
        assertThat(ledger.balance(BookRef.account(nubank.id()), LocalDate.of(2026, 10, 15))).isEqualTo(Money.of("900.00"));
        assertThat(ledger.balance(BookRef.account(nubank.id()), LocalDate.of(2026, 10, 20))).isEqualTo(Money.of("850.00"));

        write.deactivateBySource(USER_ID, PostingSourceType.TRANSACTION, first.id(), "VOID", NOW);
        assertThat(ledgerOf(USER_ID).balance(BookRef.account(nubank.id()), LocalDate.of(2026, 10, 20))).isEqualTo(Money.of("950.00"));
    }

    @Test
    @DisplayName("CA4: o carregamento (user_id = ? AND active) usa índice parcial WHERE active, sem seq scan, com ~1.000 linhas")
    void deveUsarIndiceParcial() {
        List<Object[]> users = new ArrayList<>();
        List<Object[]> postings = new ArrayList<>();
        Timestamp now = Timestamp.from(NOW);
        for (int u = 0; u < 20; u++) {
            UUID user = UUID.fromString(String.format("00000000-0000-0000-0000-0000000c%04d", u + 1));
            users.add(new Object[] {user, "carga" + u + "@moneylog.com", now, now});
            for (int i = 0; i < 50; i++) {
                boolean active = i % 5 != 0;
                UUID account = UUID.randomUUID();
                // abertura de conta (sem FK de transação); metade em cada livro, para o índice por livro também ter dados
                postings.add(new Object[] {UUID.randomUUID(), user, UUID.randomUUID(), account, "2026-10",
                        Date.valueOf(OPENING), 1_000L + i, active, active ? null : now, active ? null : "VOID", now, now});
            }
        }
        jdbc.batchUpdate("INSERT INTO users (id, email, created_at, updated_at) VALUES (?, ?, ?, ?)", users);
        jdbc.batchUpdate("""
                INSERT INTO ledger_postings (id, user_id, source_type, source_id, book_type, book_id, competence, effective_date,
                    amount_cents, currency, active, deactivated_at, deactivation_reason, created_at, updated_at)
                VALUES (?, ?, 'ACCOUNT_OPENING', ?, 'ACCOUNT', ?, ?, ?, ?, 'BRL', ?, ?, ?, ?, ?)""", postings);
        jdbc.execute("ANALYZE ledger_postings");

        List<String> plan = jdbc.queryForList("""
                EXPLAIN SELECT * FROM ledger_postings WHERE user_id = '00000000-0000-0000-0000-0000000c0007' AND active = true
                ORDER BY effective_date, created_at, id""", String.class);

        String text = String.join("\n", plan);
        assertThat(text).as(text).doesNotContain("Seq Scan").containsPattern("Index (Only )?Scan|Bitmap Index Scan")
                .contains("ix_ledger_postings_");
    }

    @Test
    @DisplayName("CA5: lançamentos e movimentações de outro usuário não aparecem")
    void deveIsolarPorUsuario() {
        persistOpening();
        persist(expense("00000000-0000-0000-0000-0000000e0001", LocalDate.of(2026, 10, 10), "100.00"));

        assertThat(read.activeEntries(OTHER_USER_ID)).isEmpty();
        assertThat(read.entriesOfTransactions(OTHER_USER_ID, List.of(UUID.fromString("00000000-0000-0000-0000-0000000e0001"))))
                .isEmpty();
        assertThat(write.deactivateBySource(OTHER_USER_ID, PostingSourceType.ACCOUNT_OPENING, nubank.id(), "X", NOW)).isZero();
        assertThat(read.activeEntries(USER_ID)).hasSize(2);
    }

    @Test
    @DisplayName("entriesOfTransactions devolve também os inativos (estorno) agrupados pela movimentação")
    void deveLerLancamentosDeMovimentacoes() {
        Transaction first = expense("00000000-0000-0000-0000-0000000e0001", LocalDate.of(2026, 10, 10), "100.00");
        persist(first);
        write.deactivateBySource(USER_ID, PostingSourceType.TRANSACTION, first.id(), "VOID", NOW);

        Map<UUID, List<LedgerEntry>> entries = read.entriesOfTransactions(USER_ID, List.of(first.id()));

        assertThat(entries).containsOnlyKeys(first.id());
        assertThat(entries.get(first.id())).hasSize(2).allMatch(e -> !e.active());
        assertThat(read.entriesOfTransactions(USER_ID, List.of())).isEmpty();
        assertThat(read.activeEntries(USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("CA7: o cenário base de D1 §3 (tipos em conta) gravado e lido bate com o FinancialRecords em memória, em centavos")
    void deveReproduzirOCenarioBase() {
        FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
        Transaction restaurante = scenario.caso1DespesaPix();
        scenario.caso4Transferencia();
        scenario.caso5Investimento();
        scenario.caso9Reembolso(restaurante);
        Transaction estornada = scenario.caso4Transferencia();
        scenario.estornar(estornada, "lançada por engano", FinanceScenarioFactory.BASE_DATE);
        FinancialRecords records = new FinancialRecords(List.of(scenario.nubank, scenario.xp, scenario.xpInvest), List.of(),
                scenario.transactions, List.of());

        // o Nubank e a Alimentação do cenário têm os mesmos ids das linhas do @BeforeEach: troca pelas do cenário
        jdbc.update("DELETE FROM categories WHERE id = ?", alimentacao.id());
        jdbc.update("DELETE FROM accounts WHERE id = ?", nubank.id());
        for (Account account : records.accounts()) {
            SqlFixtures.insertAccount(jdbc, account);
            write.append(USER_ID, LedgerEntry.activeGroup(PostingSource.accountOpening(account.id()), account.openingPostings()));
        }
        SqlFixtures.insertCategory(jdbc, scenario.alimentacao);
        for (Transaction transaction : records.transactions()) {
            SqlFixtures.insertTransactionRow(jdbc, transaction);
            write.append(USER_ID, transaction.entries().stream().map(e -> new LedgerEntry(e.source(), e.posting(), true)).toList());
            if (!transaction.isActive()) {
                write.deactivateBySource(USER_ID, PostingSourceType.TRANSACTION, transaction.id(), "VOID", NOW);
            }
        }

        List<LedgerEntry> expected = records.entries().stream().filter(LedgerEntry::active).toList();
        List<LedgerEntry> actual = read.activeEntries(USER_ID).values().stream().flatMap(List::stream).toList();
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        Ledger memory = records.ledger();
        Ledger stored = new Ledger(actual);
        for (Account account : records.accounts()) {
            for (LocalDate day : List.of(LocalDate.of(2026, 10, 9), FinanceScenarioFactory.BASE_DATE, LocalDate.of(2026, 12, 31))) {
                assertThat(stored.balance(account.book(), day)).as(account.name() + " em " + day).isEqualTo(memory.balance(account.book(), day));
            }
        }
        assertThat(stored.balance(BookType.EXPENSE, LocalDate.of(2026, 12, 31))).isEqualTo(memory.balance(BookType.EXPENSE, LocalDate.of(2026, 12, 31)));
    }
}
