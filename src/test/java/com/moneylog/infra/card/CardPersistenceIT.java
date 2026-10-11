package com.moneylog.infra.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.domain.TransactionVoiding;
import com.moneylog.core.transaction.domain.VoidContext;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionStateInconsistentException;
import com.moneylog.infra.card.gateways.InvoiceRowGateway;
import com.moneylog.util.FixedClockConfig;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@Import(FixedClockConfig.class)
@DisplayName("Parcelas e faturas persistidas (T52)")
class CardPersistenceIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private TransactionGateway transactions;
    @Autowired
    private LedgerWriteGateway ledger;
    @Autowired
    private CardGateway cardGateway;
    @Autowired
    private FinancialRecordsGateway records;
    @Autowired
    private InvoiceRowGateway invoiceRows;

    private UUID userId;
    private Account nubank;
    private Category food;
    private CreditCard card;

    @BeforeEach
    void cenario() {
        userId = UUID.randomUUID();
        nubank = Account.open(UUID.randomUUID(), userId, "Nubank", AccountType.CHECKING, "Nubank", Money.of("20000.00"), LocalDate.of(2026, 10, 1));
        food = Category.create(UUID.randomUUID(), userId, "Alimentação", CategoryKind.EXPENSE);
        committed(() -> {
            SqlFixtures.insertUser(jdbc, userId, "persist-" + userId + "@example.com");
            SqlFixtures.insertAccount(jdbc, nubank);
            SqlFixtures.insertCategory(jdbc, food);
        });
        card = newCard(25, 5);
    }

    private CreditCard newCard(int closingDay, int dueDay) {
        CreditCard created = CreditCard.create(UUID.randomUUID(), userId, "Roxinho", "Nubank", Money.of("10000.00"), closingDay, dueDay,
                nubank.id(), LocalDate.of(2026, 9, 1));
        committed(() -> cardGateway.save(created));
        return created;
    }

    private void committed(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private TransactionHeader header(LocalDate date, String description) {
        return TransactionHeader.of(UUID.randomUUID(), userId, date, description);
    }

    private void persist(Transaction... all) {
        committed(() -> {
            for (Transaction tx : all) {
                transactions.insert(tx);
                ledger.append(userId, tx.entries());
            }
        });
    }

    private InvoiceChain chain(List<Transaction> txs) {
        return CardInvoices.chainFor(card, txs);
    }

    private Transaction purchase(String amount, int installments, LocalDate date) {
        return CardTransactions.purchase(header(date, "Compra"), Money.of(amount), card, food, installments, InvoiceChain.empty(card), date);
    }

    @Test
    @DisplayName("CA1: compra de 1.200 em 6x gravada: 6 parcelas, 6 faturas (uma por fechamento) e postings por parcela com installment_id; reidrata igual")
    void deveGravarEReidratarACompraParcelada() {
        Transaction purchase = purchase("1200.00", 6, TODAY);

        persist(purchase);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT i.number, i.total_installments, i.amount_cents, i.competence, i.effective_date, i.late_entry, i.canceled_at, v.closing_date, v.card_id,
                       v.due_date, v.reference_month, v.period_start
                FROM transaction_installments i JOIN credit_card_invoices v ON v.id = i.invoice_id
                WHERE i.transaction_id = ? ORDER BY i.number""", purchase.id());
        assertThat(rows).hasSize(6);
        assertThat(rows.stream().map(r -> ((Number) r.get("amount_cents")).longValue())).containsOnly(20_000L);
        assertThat(rows.stream().map(r -> r.get("competence").toString().strip()).toList())
                .containsExactly("2026-10", "2026-11", "2026-12", "2027-01", "2027-02", "2027-03");
        assertThat(rows.stream().map(r -> r.get("closing_date").toString()).toList())
                .containsExactly("2026-10-25", "2026-11-25", "2026-12-25", "2027-01-25", "2027-02-25", "2027-03-25");
        assertThat(rows).allSatisfy(r -> assertThat(r.get("card_id")).isEqualTo(card.id()));
        assertThat(rows.getFirst()).containsEntry("due_date", Date.valueOf("2026-11-05")).containsEntry("period_start", Date.valueOf("2026-09-25"));
        assertThat(rows.getFirst().get("reference_month").toString().strip()).isEqualTo("2026-11");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_invoices WHERE card_id = ?", Integer.class, card.id())).isEqualTo(6);

        List<Map<String, Object>> postings = jdbc.queryForList("""
                SELECT book_type, amount_cents, installment_id, effective_date FROM ledger_postings WHERE transaction_id = ? AND active
                ORDER BY effective_date, book_type""", purchase.id());
        assertThat(postings).hasSize(12);
        assertThat(postings.stream().filter(p -> p.get("book_type").equals("EXPENSE")).map(p -> ((Number) p.get("amount_cents")).longValue()))
                .containsOnly(20_000L);
        assertThat(postings.stream().filter(p -> p.get("book_type").equals("CARD")).map(p -> ((Number) p.get("amount_cents")).longValue()))
                .containsOnly(-20_000L);
        assertThat(postings).allSatisfy(p -> assertThat(p.get("installment_id")).isNotNull());
        assertThat(postings.stream().map(p -> p.get("installment_id")).distinct()).hasSize(6);

        Transaction loaded = transactions.findById(userId, purchase.id()).orElseThrow().value();
        assertThat(loaded.installments()).hasSameSizeAs(purchase.installments());
        for (int k = 0; k < 6; k++) {
            TransactionInstallment a = purchase.installments().get(k);
            TransactionInstallment b = loaded.installments().get(k);
            assertThat(b).hasToString(a.toString());
            assertThat(b.id()).isEqualTo(a.id());
            assertThat(b.competence()).isEqualTo(a.competence());
            assertThat(b.lateEntry()).isEqualTo(a.lateEntry());
        }
        assertThat(loaded.cardId()).isEqualTo(card.id());
        assertThat(loaded.entries()).hasSize(12);
        assertThat(loaded.version()).isZero();
    }

    @Test
    @DisplayName("CA2: duas transações no mesmo ciclo fazem findOrCreate do mesmo fechamento: uma linha, o mesmo id e nenhuma exceção (20 repetições)")
    void deveCriarUmaLinhaSoEmCorrida() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int repetition = 0; repetition < 20; repetition++) {
                LocalDate closing = LocalDate.of(2026, 10, 25).plusMonths(repetition);
                CyclicBarrier barrier = new CyclicBarrier(2);
                List<Future<UUID>> results = new ArrayList<>();
                for (int thread = 0; thread < 2; thread++) {
                    results.add(pool.submit(() -> {
                        barrier.await();
                        return new TransactionTemplate(transactionManager).execute(status -> invoiceRows.findOrCreate(userId, card, closing));
                    }));
                }
                UUID first = results.get(0).get();
                UUID second = results.get(1).get();
                assertThat(first).isEqualTo(second).isEqualTo(InvoiceIds.of(card.id(), closing));
                assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_invoices WHERE card_id = ? AND closing_date = ?", Integer.class,
                        card.id(), Date.valueOf(closing))).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("findOrCreate de uma data que não é fechamento do calendário é erro de ligação (IllegalStateException), nada gravado")
    void deveRecusarDataQueNaoEFechamento() {
        assertThatThrownBy(() -> committedCall(() -> invoiceRows.findOrCreate(userId, card, LocalDate.of(2026, 10, 24))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_invoices WHERE card_id = ?", Integer.class, card.id())).isZero();
    }

    private <T> T committedCall(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    @Test
    @DisplayName("CA3: o id da linha é sempre o determinístico de (cartão, fechamento) e fatura sem item não tem linha")
    void deveTerIdDeterministicoESemLinhaVazia() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_invoices WHERE card_id = ?", Integer.class, card.id())).isZero();

        persist(purchase("300.00", 3, TODAY));

        List<Map<String, Object>> invoices = jdbc.queryForList("SELECT id, closing_date FROM credit_card_invoices WHERE card_id = ?", card.id());
        assertThat(invoices).hasSize(3);
        for (Map<String, Object> invoice : invoices) {
            LocalDate closing = ((Date) invoice.get("closing_date")).toLocalDate();
            assertThat(invoice.get("id")).isEqualTo(InvoiceIds.of(card.id(), closing));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_invoices WHERE card_id = ? AND closing_date = ?", Integer.class,
                card.id(), Date.valueOf("2026-09-25"))).isZero();
    }

    @Test
    @DisplayName("CA4: reembolso de 50 em 12/10 e pagamento de 150 gravados preservam fatura e lançamento tardio; a cadeia reconstruída bate")
    void deveGravarReembolsoEPagamentoNoCartao() {
        Transaction purchase = purchase("400.00", 1, TODAY);
        Transaction refund = CardTransactions.refundToCard(header(LocalDate.of(2026, 10, 12), "Estorno"), Money.of("50.00"), card, food, null,
                chain(List.of(purchase)), TODAY);
        Transaction payment = CardTransactions.invoicePayment(header(LocalDate.of(2026, 10, 26), "Fatura"), Money.of("150.00"), nubank, card,
                LocalDate.of(2026, 10, 25), chain(List.of(purchase, refund)), LocalDate.of(2026, 10, 26));

        persist(purchase, refund, payment);

        List<Transaction> loaded = transactions.findAllOfCard(userId, card.id());
        assertThat(loaded).hasSize(3);
        Transaction loadedRefund = loaded.stream().filter(t -> t.type() == TransactionType.REFUND).findFirst().orElseThrow();
        Transaction loadedPayment = loaded.stream().filter(t -> t.type() == TransactionType.INVOICE_PAYMENT).findFirst().orElseThrow();
        assertThat(loadedRefund.invoiceClosingDate()).isEqualTo(refund.invoiceClosingDate());
        assertThat(loadedRefund.lateEntry()).isEqualTo(refund.lateEntry());
        assertThat(loadedRefund.cardId()).isEqualTo(card.id());
        assertThat(loadedPayment.invoiceClosingDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(loadedPayment.accountId()).isEqualTo(nubank.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE card_id = ? AND invoice_id IS NOT NULL", Integer.class, card.id()))
                .isEqualTo(2);
        assertCardChainsEqual(chain(List.of(purchase, refund, payment)), chain(loaded), LocalDate.of(2026, 10, 26));
    }

    @Test
    @DisplayName("CA4: a cadeia lida do banco bate com a da memória em 20 cenários gerados (compras, créditos e pagamentos)")
    void deveManterACadeiaIgualEm20CenariosGerados() {
        for (long seed = 1; seed <= 20; seed++) {
            Random random = new Random(seed);
            CreditCard scenarioCard = newCard(List.of(5, 15, 25, 31).get(random.nextInt(4)), 1 + random.nextInt(28));
            List<Transaction> txs = new ArrayList<>();
            int operations = 5 + random.nextInt(6);
            for (int op = 0; op < operations; op++) {
                try {
                    InvoiceChain current = CardInvoices.chainFor(scenarioCard, txs);
                    int kind = random.nextInt(10);
                    if (kind < 6) {
                        LocalDate date = TODAY.minusDays(random.nextInt(80));
                        txs.add(CardTransactions.purchase(header(date, "Compra"), Money.ofCents(100 + random.nextInt(250_000), CurrencyCode.BRL),
                                scenarioCard, food, 1 + random.nextInt(12), current, TODAY));
                    } else if (kind < 8) {
                        LocalDate date = TODAY.minusDays(random.nextInt(40));
                        txs.add(CardTransactions.refundToCard(header(date, "Crédito"), Money.ofCents(100 + random.nextInt(10_000), CurrencyCode.BRL),
                                scenarioCard, food, null, current, TODAY));
                    } else {
                        CreditCardInvoice payable = current.invoices().stream()
                                .filter(i -> i.statusAt(TODAY) == InvoiceStatus.CLOSED || i.statusAt(TODAY) == InvoiceStatus.OVERDUE
                                        || i.statusAt(TODAY) == InvoiceStatus.OPEN)
                                .filter(i -> i.outstanding().isPositive()).findFirst().orElse(null);
                        if (payable != null) {
                            long cents = random.nextBoolean() ? payable.outstanding().cents() : 1 + random.nextInt((int) payable.outstanding().cents() + 5_000);
                            txs.add(CardTransactions.invoicePayment(header(TODAY, "Fatura"), Money.ofCents(cents, CurrencyCode.BRL), nubank,
                                    scenarioCard, payable.closingDate(), current, TODAY));
                        }
                    }
                } catch (DomainException expected) {
                }
            }
            card = scenarioCard;
            persist(txs.toArray(Transaction[]::new));

            List<Transaction> loaded = transactions.findAllOfCard(userId, scenarioCard.id());
            assertThat(loaded).as("seed %d", seed).hasSameSizeAs(txs);
            assertCardChainsEqual(CardInvoices.chainFor(scenarioCard, txs), CardInvoices.chainFor(scenarioCard, loaded), TODAY);
            FinancialRecords all = records.load(userId);
            Money sqlBalance = Money.ofCents(jdbc.queryForObject(
                    "SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE user_id = ? AND book_type = 'CARD' AND book_id = ? AND active",
                    Long.class, userId, scenarioCard.id()), CurrencyCode.BRL);
            assertThat(all.ledger().balanceAllDates(scenarioCard.book())).as("seed %d: razão em memória × SQL", seed).isEqualTo(sqlBalance);
        }
    }

    private static void assertCardChainsEqual(InvoiceChain expected, InvoiceChain actual, LocalDate today) {
        List<CreditCardInvoice> a = expected.invoices();
        List<CreditCardInvoice> b = actual.invoices();
        assertThat(b).hasSameSizeAs(a);
        for (int i = 0; i < a.size(); i++) {
            assertThat(b.get(i).closingDate()).isEqualTo(a.get(i).closingDate());
            assertThat(b.get(i).total()).as("total " + a.get(i).closingDate()).isEqualTo(a.get(i).total());
            assertThat(b.get(i).paid()).isEqualTo(a.get(i).paid());
            assertThat(b.get(i).carryIn()).isEqualTo(a.get(i).carryIn());
            assertThat(b.get(i).outstanding()).isEqualTo(a.get(i).outstanding());
            assertThat(b.get(i).creditCarry()).isEqualTo(a.get(i).creditCarry());
            assertThat(b.get(i).statusAt(today)).isEqualTo(a.get(i).statusAt(today));
            assertThat(b.get(i).items()).hasSameSizeAs(a.get(i).items());
        }
    }

    @Test
    @DisplayName("CA5: carregamento único traz o cartão com as regras e as movimentações; pagas as 6 faturas, o livro CARD fecha em zero (memória e SQL)")
    void deveFecharOLivroDoCartaoEmZero() {
        Transaction purchase = purchase("1200.00", 6, TODAY);
        List<Transaction> txs = new ArrayList<>(List.of(purchase));
        for (int month = 0; month < 6; month++) {
            LocalDate closing = LocalDate.of(2026, 10, 25).plusMonths(month);
            txs.add(CardTransactions.invoicePayment(header(closing, "Fatura " + (month + 1)), Money.of("200.00"), nubank, card, closing,
                    chain(txs), closing));
        }
        persist(txs.toArray(Transaction[]::new));

        FinancialRecords loaded = records.load(userId);

        assertThat(loaded.cards()).hasSize(1);
        CreditCard loadedCard = loaded.cards().getFirst();
        assertThat(loadedCard.id()).isEqualTo(card.id());
        assertThat(loadedCard.billingRules()).isEqualTo(card.billingRules());
        assertThat(loaded.transactions().stream().filter(t -> card.id().equals(t.cardId()))).hasSize(7);
        assertThat(loaded.ledger().balanceAllDates(card.book())).isEqualTo(Money.of("0.00"));
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE book_type = 'CARD' AND book_id = ? AND active",
                Long.class, card.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE book_type = 'EXPENSE' AND user_id = ? AND active",
                Long.class, userId)).isEqualTo(120_000L);
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE book_type = 'ACCOUNT' AND book_id = ? AND active",
                Long.class, nubank.id())).isEqualTo(-120_000L);
        assertThat(chain(loaded.transactions()).creditBalance()).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("CA5: as regras de fechamento gravadas (várias, depois de uma mudança de dias) voltam iguais no carregamento único")
    void deveCarregarVariasRegras() {
        CreditCard changing = newCard(25, 5);
        changing.changeBillingDays(10, 28, TODAY);
        committed(() -> cardGateway.save(changing));

        CreditCard loaded = records.load(userId).cards().stream().filter(c -> c.id().equals(changing.id())).findFirst().orElseThrow();

        assertThat(loaded.billingRules()).hasSize(2).isEqualTo(changing.billingRules());
        assertThat(loaded.closingDay()).isEqualTo(10);
        assertThat(loaded.version()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_billing_rules WHERE card_id = ?", Integer.class, changing.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("CA6: parcela apontando para fatura de outro cartão é TransactionStateInconsistentException (nada parcial)")
    void deveRecusarEstadoInconsistente() {
        Transaction purchase = purchase("100.00", 1, TODAY);
        persist(purchase);
        CreditCard other = newCard(25, 5);
        UUID foreignInvoice = committedCall(() -> invoiceRows.findOrCreate(userId, other, LocalDate.of(2026, 10, 25)));
        committed(() -> jdbc.update("UPDATE transaction_installments SET invoice_id = ? WHERE transaction_id = ?", foreignInvoice, purchase.id()));

        assertThatThrownBy(() -> transactions.findById(userId, purchase.id())).isInstanceOf(TransactionStateInconsistentException.class)
                .hasMessageContaining("outro cartão");
        assertThatThrownBy(() -> records.load(userId)).isInstanceOf(TransactionStateInconsistentException.class);
        assertThatThrownBy(() -> transactions.findAllOfCard(userId, card.id())).isInstanceOf(TransactionStateInconsistentException.class);
    }

    @Test
    @DisplayName("parcelas que não batem com o número declarado pela compra são recusadas na reidratação")
    void deveRecusarParcelasFaltando() {
        Transaction purchase = purchase("300.00", 3, TODAY);
        persist(purchase);
        committed(() -> {
            jdbc.update("DELETE FROM ledger_postings WHERE installment_id IN (SELECT id FROM transaction_installments WHERE transaction_id = ? AND number = 3)", purchase.id());
            jdbc.update("DELETE FROM transaction_installments WHERE transaction_id = ? AND number = 3", purchase.id());
        });

        assertThatThrownBy(() -> transactions.findById(userId, purchase.id())).isInstanceOf(TransactionStateInconsistentException.class);
    }

    @Test
    @DisplayName("estornar a compra grava VOIDED, cancela as parcelas e desativa os lançamentos; a cadeia fica com total zero")
    void deveGravarOEstornoDaCompra() {
        Transaction purchase = purchase("1200.00", 6, TODAY);
        persist(purchase);
        Transaction current = transactions.findById(userId, purchase.id()).orElseThrow().value();
        TransactionVoiding.voidTransaction(current, VoidContext.of("erro de digitação", NOW, TODAY).withCard(card, chain(List.of(current))));

        committed(() -> {
            transactions.update(current);
            ledger.deactivateBySource(userId, PostingSourceType.TRANSACTION, current.id(), "VOID", NOW);
        });

        Transaction reloaded = transactions.findById(userId, purchase.id()).orElseThrow().value();
        assertThat(reloaded.status()).isEqualTo(TransactionStatus.VOIDED);
        assertThat(reloaded.installments()).allMatch(TransactionInstallment::isCanceled);
        assertThat(reloaded.version()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_installments WHERE transaction_id = ? AND canceled_at IS NOT NULL",
                Integer.class, purchase.id())).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE transaction_id = ? AND active", Integer.class, purchase.id())).isZero();
        assertThat(chain(transactions.findAllOfCard(userId, card.id())).current(TODAY).total()).isEqualTo(Money.of("0.00"));
        assertThat(records.load(userId).ledger().balanceAllDates(card.book())).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("findAllOfCard filtra por usuário e por cartão")
    void deveIsolarPorUsuarioECartao() {
        persist(purchase("100.00", 1, TODAY));
        CreditCard other = newCard(10, 20);
        UUID otherUser = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, otherUser, "outro-" + otherUser + "@example.com"));

        assertThat(transactions.findAllOfCard(userId, card.id())).hasSize(1);
        assertThat(transactions.findAllOfCard(userId, other.id())).isEmpty();
        assertThat(transactions.findAllOfCard(otherUser, card.id())).isEmpty();
        assertThat(cardGateway.findById(otherUser, card.id())).isEmpty();
        Persisted<CreditCard> mine = cardGateway.findById(userId, card.id()).orElseThrow();
        assertThat(mine.value().billingRules()).isEqualTo(card.billingRules());
    }
}
