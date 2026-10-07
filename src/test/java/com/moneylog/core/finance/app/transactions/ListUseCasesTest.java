package com.moneylog.core.finance.app.transactions;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.transactions.usecase.ListEntriesUseCase.ListEntriesQuery;
import com.moneylog.core.finance.app.transactions.usecase.ListEntriesUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.ListTransactionsUseCase.ListTransactionsQuery;
import com.moneylog.core.finance.app.transactions.usecase.ListTransactionsUseCaseImpl;
import com.moneylog.core.finance.domain.CashFlowReport;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.ListScenario;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.query.EntryFilter;
import com.moneylog.core.transaction.app.query.TransactionFilter;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Casos de uso da lista (T28, S-79, S-80)")
class ListUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    @Mock
    private TransactionGateway transactions;
    @Mock
    private FinancialRecordsGateway recordsGateway;
    @Mock
    private AccountGateway accounts;
    @Mock
    private CategoryGateway categories;

    private final ListScenario s = new ListScenario();
    private final FinancialRecords records = s.records();
    private ListTransactionsUseCaseImpl listTransactions;
    private ListEntriesUseCaseImpl listEntries;

    @BeforeEach
    void setUp() {
        when(recordsGateway.load(USER_ID)).thenReturn(records);
        when(categories.list(USER_ID, null, true)).thenReturn(List.of(s.alimentacao, s.salario, s.transporte));
        listTransactions = new ListTransactionsUseCaseImpl(transactions, recordsGateway,
                new TransactionDetailsAssembler(transactions, accounts, categories));
        listEntries = new ListEntriesUseCaseImpl(recordsGateway, categories);
    }

    private static TransactionFilter october(UUID accountId, TransactionStatus status) {
        return new TransactionFilter(FROM, TO, null, accountId, null, null, status, null);
    }

    private void pageIs(TransactionFilter filter, PageQuery page, boolean hasNext, Transaction... items) {
        when(transactions.list(USER_ID, filter, page)).thenReturn(new Slice<>(
                java.util.Arrays.stream(items).map(t -> new Persisted<>(t, NOW, NOW)).toList(), hasNext));
    }

    @Test
    @DisplayName("sem conta: o fluxo vem de CashFlowReport.between sobre os MESMOS registros do carregamento (igual ao do dashboard); count conta as ativas (a transferência CASH → CASH entra)")
    void deveCalcularOsTotaisPeloCaminhoUnico() {
        TransactionFilter filter = october(null, null);
        pageIs(filter, new PageQuery(1, 50), false, s.aplicacao, s.debito);

        var listing = listTransactions.handle(new ListTransactionsQuery(USER_ID, filter, new PageQuery(1, 50)));

        CashFlowReport dashboard = CashFlowReport.between(records, records.ledger(), FROM, TO);
        assertThat(listing.cashFlow().in()).isEqualTo(dashboard.in()).isEqualTo(Money.of("3050.00"));
        assertThat(listing.cashFlow().out()).isEqualTo(dashboard.out()).isEqualTo(Money.of("640.00"));
        assertThat(listing.cashFlow().net()).isEqualTo(dashboard.net());
        assertThat(listing.count()).isEqualTo(6);
        assertThat(listing.items().items()).extracting(d -> d.transaction().id()).containsExactly(s.aplicacao.id(), s.debito.id());
        verify(recordsGateway).load(USER_ID);
    }

    @Test
    @DisplayName("os detalhes da página vêm dos registros: impacto, contas, categoria e flags (despesa com reembolso ativo: HAS_ACTIVE_REFUNDS)")
    void deveMontarOsDetalhesSemConsultarPorItem() {
        TransactionFilter filter = october(null, null);
        pageIs(filter, new PageQuery(1, 10), true, s.pix, s.reembolso);

        var listing = listTransactions.handle(new ListTransactionsQuery(USER_ID, filter, new PageQuery(1, 10)));

        assertThat(listing.items().hasNext()).isTrue();
        TransactionDetails pix = listing.items().items().get(0);
        assertThat(pix.account()).isEqualTo(s.nubank);
        assertThat(pix.category()).isEqualTo(s.alimentacao);
        assertThat(pix.impact().cash()).isEqualTo(Money.of("-100.00"));
        assertThat(pix.flags().voidBlockedBy()).isEqualTo(VoidBlockedBy.HAS_ACTIVE_REFUNDS);
        TransactionDetails refund = listing.items().items().get(1);
        assertThat(refund.refundOfDescription()).isEqualTo("Mercado do bairro");
        assertThat(refund.flags().voidable()).isTrue();
        verify(transactions, org.mockito.Mockito.never()).findById(any(), any());
        verify(transactions, org.mockito.Mockito.never()).sumActiveRefundsOf(any(), any());
    }

    @Test
    @DisplayName("com conta: o fluxo vem de CashFlowReport.forAccount (a transferência é entrada na XP)")
    void deveCalcularOFluxoDaConta() {
        TransactionFilter filter = october(s.xp.id(), null);
        pageIs(filter, new PageQuery(1, 10), false, s.transferencia);

        var listing = listTransactions.handle(new ListTransactionsQuery(USER_ID, filter, new PageQuery(1, 10)));

        assertThat(listing.cashFlow().in()).isEqualTo(Money.of("1000.00"));
        assertThat(listing.cashFlow().out()).isEqualTo(Money.of("0.00"));
        assertThat(listing.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("status=VOIDED: count 0 e fluxo zerado, embora a página traga as estornadas (S-80)")
    void deveZerarOsTotaisDasEstornadas() {
        TransactionFilter filter = october(null, TransactionStatus.VOIDED);
        pageIs(filter, new PageQuery(1, 10), false, s.estornada);

        var listing = listTransactions.handle(new ListTransactionsQuery(USER_ID, filter, new PageQuery(1, 10)));

        assertThat(listing.items().items()).hasSize(1);
        assertThat(listing.items().items().get(0).flags().editable()).isFalse();
        assertThat(listing.count()).isZero();
        assertThat(listing.cashFlow().in()).isEqualTo(Money.of("0.00"));
        assertThat(listing.cashFlow().out()).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("lançamentos: EntryReport.of sobre os mesmos registros (total e byCategory iguais ao da calculadora), linhas por data decrescente")
    void deveListarLancamentosPeloCaminhoUnico() {
        Competence outubro = Competence.of(2026, 10);

        var listing = listEntries.handle(new ListEntriesQuery(USER_ID, outubro, EntryLens.CONSUMPTION, new EntryFilter(null, null, null), new PageQuery(1, 10)));

        EntryReport direct = EntryReport.of(records, records.ledger(), EntryLens.CONSUMPTION, outubro);
        assertThat(listing.total()).isEqualTo(direct.total()).isEqualTo(Money.of("90.00"));
        assertThat(listing.items().items()).hasSize(3);
        assertThat(listing.items().items().get(0).line().date()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(listing.byCategory()).extracting(c -> c.category().name()).containsExactly("Alimentação", "Transporte");
        assertThat(listing.byCategory()).extracting(c -> c.amount()).containsExactly(Money.of("50.00"), Money.of("40.00"));
    }

    @Test
    @DisplayName("lançamentos: filtro vira predicado (o total é do filtro inteiro); a página recorta as linhas")
    void deveFiltrarEPaginarLancamentos() {
        Competence outubro = Competence.of(2026, 10);

        var pix = listEntries.handle(new ListEntriesQuery(USER_ID, outubro, EntryLens.CONSUMPTION,
                new EntryFilter(null, s.nubank.id(), PaymentMethod.PIX), new PageQuery(1, 10)));
        var paged = listEntries.handle(new ListEntriesQuery(USER_ID, outubro, EntryLens.CONSUMPTION, new EntryFilter(null, null, null), new PageQuery(2, 2)));
        var beyond = listEntries.handle(new ListEntriesQuery(USER_ID, outubro, EntryLens.CONSUMPTION, new EntryFilter(null, null, null), new PageQuery(9, 2)));

        assertThat(pix.total()).isEqualTo(Money.of("100.00"));
        assertThat(pix.items().items()).hasSize(1);
        assertThat(paged.items().items()).hasSize(1);
        assertThat(paged.items().hasNext()).isFalse();
        assertThat(paged.total()).isEqualTo(Money.of("90.00"));
        assertThat(beyond.items().items()).isEmpty();
        assertThat(listEntries.handle(new ListEntriesQuery(USER_ID, outubro, EntryLens.INCOME, new EntryFilter(null, null, null), new PageQuery(1, 10))).total())
                .isEqualTo(Money.of("3000.00"));
    }

    @Test
    @DisplayName("categoria que não existe mais para o usuário: CATEGORY_NOT_FOUND (dado inconsistente não passa em silêncio)")
    void deveFalharComCategoriaDesconhecida() {
        when(categories.list(eq(USER_ID), any(), eq(true))).thenReturn(List.of(s.salario));

        assertThatThrownBy(() -> listEntries.handle(new ListEntriesQuery(USER_ID, Competence.of(2026, 10), EntryLens.CONSUMPTION,
                new EntryFilter(null, null, null), new PageQuery(1, 10)))).isInstanceOf(CategoryNotFoundException.class);
    }
}
