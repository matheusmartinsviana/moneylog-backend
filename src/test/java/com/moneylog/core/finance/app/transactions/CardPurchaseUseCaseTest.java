package com.moneylog.core.finance.app.transactions;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InstallmentStatus;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.exception.CardExceptions.CardArchivedException;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.card.exception.CardExceptions.InvalidInstallmentCountException;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryKindMismatchException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.TransactionDetails.InstallmentView;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.CardPurchase;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCaseImpl;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.money.MoneyExceptions.InvalidMoneyAllocationException;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionTypeNotAvailableException;

/** Compra no cartão, à vista e parcelada (T53), pelo cenário base de D1 §3 (Roxinho fecha 25 e vence 5; relógio 10/10/2026). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Casos de uso: compra no cartão (T53)")
class CardPurchaseUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final UUID TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000e0001");
    private static final UUID CARD_ID = CreditCardTestFactory.ROXINHO_ID;

    @Mock
    private TransactionGateway transactions;
    @Mock
    private AccountGateway accounts;
    @Mock
    private CategoryGateway categories;
    @Mock
    private CardGateway cards;
    @Mock
    private LedgerWriteGateway ledger;
    @Mock
    private IdGenerator ids;
    @Mock
    private IdempotencyGateway idempotency;
    @Mock
    private TimeProvider time;

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private AccountTransactionFactory factory;
    private CreateTransactionUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        when(time.today()).thenReturn(TODAY);
        when(time.now()).thenReturn(NOW);
        when(ids.next()).thenReturn(TX_ID);
        when(categories.findById(USER_ID, scenario.alimentacao.id())).thenReturn(Optional.of(scenario.alimentacao));
        when(categories.findById(USER_ID, scenario.salario.id())).thenReturn(Optional.of(scenario.salario));
        cardIs(scenario.roxinho);
        when(transactions.findAllOfCard(USER_ID, CARD_ID)).thenAnswer(call -> new ArrayList<>(scenario.transactions));
        when(transactions.insert(any())).thenAnswer(call -> new Persisted<>(call.getArgument(0), NOW, NOW));
        when(transactions.sumActiveRefundsOf(any(), any())).thenReturn(Money.of("0.00"));
        factory = new AccountTransactionFactory(transactions, accounts, categories, cards, time);
        useCase = new CreateTransactionUseCaseImpl(transactions, factory, ledger, ids,
                new TransactionDetailsAssembler(transactions, accounts, categories, cards, time), idempotency, time);
    }

    private void cardIs(CreditCard card) {
        when(cards.findById(USER_ID, card.id())).thenReturn(Optional.of(new Persisted<>(card, NOW, NOW)));
    }

    private static Common common(String amount, LocalDate date, String description) {
        return new Common(USER_ID, Money.of(amount), date, description, null);
    }

    private TransactionDetails purchase(String amount, int installments) {
        return useCase.handle(new CardPurchase(common(amount, TODAY, "Notebook"), CARD_ID, scenario.alimentacao.id(), installments));
    }

    @Test
    @DisplayName("CA1: 1.200,00 em 6x: 6 parcelas de 200, faturas 25/10..25/03, caixa 0, consumo por competência, comprometido 1.000, patrimônio −200")
    void deveCriarACompraParcelada() {
        TransactionDetails details = purchase("1200.00", 6);

        Transaction saved = details.transaction();
        assertThat(saved.type()).isEqualTo(TransactionType.CARD_PURCHASE);
        assertThat(saved.installments()).hasSize(6);
        assertThat(saved.installments()).allSatisfy(i -> {
            assertThat(i.amount()).isEqualTo(Money.of("200.00"));
            assertThat(i.lateEntry()).isFalse();
        });
        assertThat(saved.installments().stream().map(i -> i.invoiceClosingDate().toString()).toList())
                .containsExactly("2026-10-25", "2026-11-25", "2026-12-25", "2027-01-25", "2027-02-25", "2027-03-25");
        assertThat(details.cardInfo().installments().stream().map(i -> i.competence().toString()).toList())
                .containsExactly("2026-10", "2026-11", "2026-12", "2027-01", "2027-02", "2027-03");
        assertThat(details.cardInfo().installments()).extracting(InstallmentView::status).containsOnly(InstallmentStatus.PENDING);
        assertThat(details.cardInfo().installments().getFirst().invoiceId()).isEqualTo(InvoiceIds.of(CARD_ID, LocalDate.of(2026, 10, 25)));
        assertThat(details.cardInfo().installments().getFirst().invoiceReferenceMonth().toString()).isEqualTo("2026-11");
        assertThat(details.cardInfo().installments().getFirst().invoiceDueDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(details.cardInfo().invoice()).isNull();
        assertThat(details.impact().cash()).isEqualTo(Money.of("0.00"));
        assertThat(details.impact().consumption()).hasSize(6);
        assertThat(details.impact().consumption()).allSatisfy(c -> assertThat(c.amount()).isEqualTo(Money.of("200.00")));
        assertThat(details.impact().netWorth()).isEqualTo(Money.of("-200.00"));
        assertThat(details.impact().commitment()).isEqualTo(Money.of("1000.00"));
        assertThat(details.flags().editable()).as("edição limitada (T57)").isTrue();
        assertThat(details.flags().voidable()).as("nenhuma parcela em fatura fechada (T57)").isTrue();
        assertThat(details.account()).isNull();
    }

    @Test
    @DisplayName("CA1: grava a compra e depois os lançamentos balanceados por parcela (EXPENSE +200 / CARD −200), nenhum posting de conta")
    void deveGravarPostingsPorParcela() {
        purchase("1200.00", 6);

        InOrder order = inOrder(transactions, ledger);
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        order.verify(transactions).insert(saved.capture());
        ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
        order.verify(ledger).append(org.mockito.ArgumentMatchers.eq(USER_ID), entries.capture());
        assertThat(entries.getValue()).hasSize(12).isEqualTo(saved.getValue().entries());
        assertThat(entries.getValue().stream().map(e -> e.posting().bookType().name()).distinct().toList())
                .containsExactlyInAnyOrder("EXPENSE", "CARD");
        assertThat(entries.getValue().stream().map(e -> e.source().installmentId()).distinct()).hasSize(6).doesNotContainNull();
        assertThat(entries.getValue().stream().mapToLong(e -> e.posting().amount().cents()).sum()).isZero();
        assertThat(entries.getValue().stream().filter(e -> e.posting().bookType().name().equals("CARD"))
                .mapToLong(e -> e.posting().amount().cents()).sum()).isEqualTo(-120_000L);
    }

    @Test
    @DisplayName("trava o cartão antes de ler a cadeia e a compra só é montada depois da trava (ADR-006)")
    void deveTravarOCartaoAntesDeLer() {
        purchase("100.00", 1);

        InOrder order = inOrder(cards, transactions);
        order.verify(cards).lockForUpdate(USER_ID, CARD_ID);
        order.verify(cards).findById(USER_ID, CARD_ID);
        order.verify(transactions).findAllOfCard(USER_ID, CARD_ID);
        order.verify(transactions).insert(any());
    }

    @Test
    @DisplayName("CA2: 1.000,00 em 3x vira 333,34 / 333,33 / 333,33; à vista gera uma parcela e nenhum comprometido")
    void deveDividirEmCentavos() {
        TransactionDetails parcelada = purchase("1000.00", 3);
        TransactionDetails aVista = purchase("90.00", 1);

        assertThat(parcelada.transaction().installments().stream().map(i -> i.amount().toPlainString()).toList())
                .containsExactly("333.34", "333.33", "333.33");
        assertThat(aVista.transaction().installments()).hasSize(1);
        assertThat(aVista.impact().commitment()).isEqualTo(Money.of("0.00"));
        assertThat(aVista.impact().netWorth()).isEqualTo(Money.of("-90.00"));
    }

    @Test
    @DisplayName("CA3: fatura de 25/10 fechada e não paga recebe a compra de 20/10 como lançamento tardio (relógio 27/10)")
    void deveLancarComoTardioEmFaturaFechada() {
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();
        when(time.today()).thenReturn(LocalDate.of(2026, 10, 27));

        TransactionDetails details = useCase.handle(new CardPurchase(common("90.00", LocalDate.of(2026, 10, 20), "Tardia"), CARD_ID,
                scenario.alimentacao.id(), 1));

        assertThat(details.transaction().installments().getFirst().lateEntry()).isTrue();
        assertThat(details.transaction().installments().getFirst().invoiceClosingDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(details.cardInfo().installments().getFirst().status()).isEqualTo(InstallmentStatus.PENDING);
    }

    @Test
    @DisplayName("CA3: com a fatura de 25/10 já paga, a compra vai para a corrente (25/11) com lançamento tardio, nunca para a PAID")
    void deveMandarParaACorrenteSeAFaturaEstaPaga() {
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();
        scenario.pagarFatura("1283.00", LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26));
        when(time.today()).thenReturn(LocalDate.of(2026, 10, 27));

        TransactionDetails details = useCase.handle(new CardPurchase(common("90.00", LocalDate.of(2026, 10, 20), "Tardia"), CARD_ID,
                scenario.alimentacao.id(), 1));

        assertThat(details.transaction().installments().getFirst().lateEntry()).isTrue();
        assertThat(details.transaction().installments().getFirst().invoiceClosingDate()).isEqualTo(LocalDate.of(2026, 11, 25));
    }

    @Test
    @DisplayName("as parcelas das faturas pagas e vencidas aparecem como PAID e OVERDUE (derivado da fatura) e deixam de ser compromisso")
    void deveDerivarOStatusDaParcelaDaFatura() {
        TransactionDetails first = purchase("1200.00", 6);
        scenario.transactions.add(first.transaction());
        scenario.pagarFatura("200.00", LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26));
        when(time.today()).thenReturn(LocalDate.of(2026, 12, 10));

        TransactionDetails later = new TransactionDetailsAssembler(transactions, accounts, categories, cards, time)
                .assemble(new Persisted<>(first.transaction(), NOW, NOW));

        assertThat(later.cardInfo().installments().stream().map(InstallmentView::status).toList())
                .containsExactly(InstallmentStatus.PAID, InstallmentStatus.OVERDUE, InstallmentStatus.PENDING, InstallmentStatus.PENDING,
                        InstallmentStatus.PENDING, InstallmentStatus.PENDING);
        // em 10/12: as faturas de 25/10, 25/11 e a corrente (25/12) já não são FUTURE; restam 25/01, 25/02 e 25/03
        assertThat(later.impact().commitment()).isEqualTo(Money.of("600.00"));
    }

    @Test
    @DisplayName("CA5: cartão arquivado é CARD_ARCHIVED; cartão de outro usuário ou inexistente é CARD_NOT_FOUND; nada é gravado")
    void deveRecusarCartaoInvalido() {
        cardIs(CreditCardTestFactory.umCartaoArquivado());
        assertThatThrownBy(() -> purchase("100.00", 1)).isInstanceOf(CardArchivedException.class);

        when(cards.findById(USER_ID, CARD_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> purchase("100.00", 1)).isInstanceOf(CardNotFoundException.class);

        verify(transactions, never()).insert(any());
        verify(ledger, never()).append(any(), any());
    }

    @Test
    @DisplayName("CA5: categoria de receita é CATEGORY_KIND_MISMATCH, categoria inexistente é 404; nada é gravado")
    void deveRecusarCategoriaInvalida() {
        assertThatThrownBy(() -> useCase.handle(new CardPurchase(common("100.00", TODAY, "X"), CARD_ID, scenario.salario.id(), 1)))
                .isInstanceOf(CategoryKindMismatchException.class);
        UUID missing = UUID.fromString("00000000-0000-0000-0000-00000000cccc");
        when(categories.findById(USER_ID, missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.handle(new CardPurchase(common("100.00", TODAY, "X"), CARD_ID, missing, 1)))
                .isInstanceOf(CategoryNotFoundException.class);

        verify(transactions, never()).insert(any());
        verify(ledger, never()).append(any(), any());
    }

    @Test
    @DisplayName("CA5: parcelas fora de 1..36 e valor menor que o número de parcelas em centavos são recusados no domínio")
    void deveRecusarParcelamentoInvalido() {
        assertThatThrownBy(() -> purchase("100.00", 0)).isInstanceOf(InvalidInstallmentCountException.class);
        assertThatThrownBy(() -> purchase("100.00", 37)).isInstanceOf(InvalidInstallmentCountException.class);
        assertThatThrownBy(() -> purchase("0.05", 6)).isInstanceOf(InvalidMoneyAllocationException.class)
                .hasFieldOrPropertyWithValue("code", "MONEY_INVALID_ALLOCATION");

        verify(transactions, never()).insert(any());
        verify(ledger, never()).append(any(), any());
    }

    @Test
    @DisplayName("sem a ligação de cartão (Etapa 2), a compra responde TRANSACTION_TYPE_NOT_AVAILABLE")
    void deveSerIndisponivelSemCartao() {
        AccountTransactionFactory accountsOnly = new AccountTransactionFactory(transactions, accounts, categories);

        assertThatThrownBy(() -> accountsOnly.build(new CardPurchase(common("100.00", TODAY, "X"), CARD_ID, scenario.alimentacao.id(), 1),
                TX_ID, com.moneylog.core.transaction.domain.TransactionOrigin.manual(), null))
                .isInstanceOf(TransactionTypeNotAvailableException.class);
    }

    @Test
    @DisplayName("comando de compra: o hash canônico muda com o cartão, a categoria e o número de parcelas, e é o mesmo para o mesmo comando")
    void deveTerHashCanonicoPorCampo() {
        CardPurchase base = new CardPurchase(common("100.00", TODAY, " Notebook "), CARD_ID, scenario.alimentacao.id(), 3);
        String hash = CanonicalCommandHash.of(base);

        assertThat(CanonicalCommandHash.of(new CardPurchase(common("100.0", TODAY, "Notebook"), CARD_ID, scenario.alimentacao.id(), 3)))
                .isEqualTo(hash);
        assertThat(CanonicalCommandHash.of(new CardPurchase(common("100.00", TODAY, "Notebook"), CARD_ID, scenario.alimentacao.id(), 4)))
                .isNotEqualTo(hash);
        assertThat(CanonicalCommandHash.of(new CardPurchase(common("100.00", TODAY, "Notebook"), UUID.randomUUID(), scenario.alimentacao.id(), 3)))
                .isNotEqualTo(hash);
        assertThat(CanonicalCommandHash.of(new CardPurchase(common("100.00", TODAY, "Notebook"), CARD_ID, scenario.salario.id(), 3)))
                .isNotEqualTo(hash);
        assertThat(CanonicalCommandHash.of(new CreateTransactionCommand.Unavailable(USER_ID, TransactionType.CARD_PURCHASE))).isNotEqualTo(hash);
    }
}
