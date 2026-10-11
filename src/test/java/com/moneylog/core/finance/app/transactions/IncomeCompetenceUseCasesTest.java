package com.moneylog.core.finance.app.transactions;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
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
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionUseCase.UpdateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionUseCaseImpl;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.gateways.TransactionRevisionGateway;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.exception.TransactionExceptions.IncomeCompetenceInvalidException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Casos de uso: receita com competência de renda (ADR-017)")
class IncomeCompetenceUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final LocalDate ADIANTAMENTO = LocalDate.of(2026, 9, 16);
    private static final UUID TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000e1001");

    @Mock
    private TransactionGateway transactions;
    @Mock
    private AccountGateway accounts;
    @Mock
    private CategoryGateway categories;
    @Mock
    private LedgerWriteGateway ledger;
    @Mock
    private TransactionRevisionGateway revisions;
    @Mock
    private IdGenerator ids;
    @Mock
    private IdempotencyGateway idempotency;
    @Mock
    private TimeProvider time;

    private final Account nubank = AccountTestFactory.umaContaCorrente();
    private final Category salary = CategoryTestFactory.umaCategoriaDeReceita();
    private CreateTransactionUseCaseImpl create;
    private UpdateTransactionUseCaseImpl update;

    @BeforeEach
    void setUp() {
        when(accounts.findById(USER_ID, nubank.id())).thenReturn(Optional.of(new Persisted<>(nubank, NOW, NOW)));
        when(categories.findById(USER_ID, salary.id())).thenReturn(Optional.of(salary));
        when(ids.next()).thenReturn(TX_ID);
        when(time.now()).thenReturn(NOW);
        when(time.today()).thenReturn(TODAY);
        when(transactions.insert(any())).thenAnswer(call -> new Persisted<>(call.getArgument(0), NOW, NOW));
        when(transactions.update(any())).thenAnswer(call -> new Persisted<>(call.getArgument(0), NOW, NOW));
        when(transactions.sumActiveRefundsOf(any(), any())).thenReturn(Money.of("0.00"));
        TransactionDetailsAssembler assembler = new TransactionDetailsAssembler(transactions, accounts, categories);
        AccountTransactionFactory factory = new AccountTransactionFactory(transactions, accounts, categories);
        create = new CreateTransactionUseCaseImpl(transactions, factory, ledger, ids, assembler, idempotency, time);
        update = new UpdateTransactionUseCaseImpl(transactions, accounts, factory, revisions, ledger, assembler, time);
    }

    private CreateTransactionCommand.Income income(Competence competence) {
        return new CreateTransactionCommand.Income(new Common(USER_ID, Money.of("1500.00"), ADIANTAMENTO, "Adiantamento", null), nubank.id(),
                salary.id(), PaymentMethod.PIX, competence);
    }

    private Transaction stored(Competence competence) {
        Transaction t = AccountTransactions.income(TransactionHeader.of(TX_ID, USER_ID, ADIANTAMENTO, "Adiantamento"), Money.of("1500.00"), nubank,
                salary, PaymentMethod.PIX, competence);
        when(transactions.findById(USER_ID, TX_ID)).thenReturn(Optional.of(new Persisted<>(t, NOW, NOW)));
        return t;
    }

    @Test
    @DisplayName("criar: grava a movimentação e depois os lançamentos; o livro INCOME vai para a competência e a conta fica na data real")
    void deveCriarComCompetencia() {
        TransactionDetails details = create.handle(income(Competence.of(2026, 10)));

        InOrder order = inOrder(transactions, ledger);
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        order.verify(transactions).insert(saved.capture());
        ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
        order.verify(ledger).append(eq(USER_ID), entries.capture());
        assertThat(saved.getValue().incomeCompetence()).isEqualTo(Competence.of(2026, 10));
        assertThat(entries.getValue()).filteredOn(e -> e.posting().bookType() == BookType.INCOME)
                .singleElement().satisfies(e -> {
                    assertThat(e.posting().competence()).isEqualTo(Competence.of(2026, 10));
                    assertThat(e.posting().effectiveDate()).isEqualTo(ADIANTAMENTO);
                });
        assertThat(entries.getValue()).filteredOn(e -> e.posting().bookType() == BookType.ACCOUNT)
                .singleElement().satisfies(e -> assertThat(e.posting().effectiveDate()).isEqualTo(ADIANTAMENTO));
        assertThat(entries.getValue().stream().map(e -> e.posting().amount().cents()).reduce(0L, Long::sum)).isZero();
        assertThat(details.impact().cash()).isEqualTo(Money.of("1500.00"));
        assertThat(details.transaction().incomeCompetence()).isEqualTo(Competence.of(2026, 10));
    }

    @Test
    @DisplayName("criar fora da janela: INCOME_COMPETENCE_INVALID e nada é gravado")
    void deveRecusarForaDaJanela() {
        assertThatThrownBy(() -> create.handle(income(Competence.of(2101, 1)))).isInstanceOf(IncomeCompetenceInvalidException.class);

        verify(transactions, never()).insert(any());
        verify(ledger, never()).append(any(), any());
    }

    @Test
    @DisplayName("editar: define a competência; a revisão guarda a versão anterior (sem competência) antes de trocar os lançamentos")
    void deveDefinirNaEdicao() {
        stored(null);

        update.handle(new UpdateTransactionCommand(TX_ID, 0, income(Competence.of(2026, 10))));

        InOrder order = inOrder(transactions, revisions, ledger);
        order.verify(transactions).update(any());
        ArgumentCaptor<Transaction> previous = ArgumentCaptor.forClass(Transaction.class);
        order.verify(revisions).save(eq(USER_ID), previous.capture(), eq(1), eq(NOW));
        order.verify(ledger).deactivateBySource(USER_ID, PostingSourceType.TRANSACTION, TX_ID, "EDIT", NOW);
        ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
        order.verify(ledger).append(eq(USER_ID), entries.capture());
        assertThat(previous.getValue().incomeCompetence()).isNull();
        assertThat(entries.getValue()).filteredOn(e -> e.posting().bookType() == BookType.INCOME).singleElement()
                .satisfies(e -> assertThat(e.posting().competence()).isEqualTo(Competence.of(2026, 10)));
    }

    @Test
    @DisplayName("editar: sem o campo limpa a competência (volta ao mês da data); a revisão guarda a anterior")
    void deveLimparNaEdicao() {
        stored(Competence.of(2026, 10));

        update.handle(new UpdateTransactionCommand(TX_ID, 0, income(null)));

        ArgumentCaptor<Transaction> previous = ArgumentCaptor.forClass(Transaction.class);
        verify(revisions).save(eq(USER_ID), previous.capture(), eq(1), eq(NOW));
        ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(ledger).append(eq(USER_ID), entries.capture());
        assertThat(previous.getValue().incomeCompetence()).isEqualTo(Competence.of(2026, 10));
        assertThat(entries.getValue()).filteredOn(e -> e.posting().bookType() == BookType.INCOME).singleElement()
                .satisfies(e -> assertThat(e.posting().competence()).isEqualTo(Competence.of(2026, 9)));
    }

    @Test
    @DisplayName("idempotência: o hash da receita sem competência é o de antes; com competência (ou outra) é diferente")
    void deveDiferenciarOHashSoQuandoInformada() {
        String sem = CanonicalCommandHash.of(income(null));
        String outubro = CanonicalCommandHash.of(income(Competence.of(2026, 10)));
        String novembro = CanonicalCommandHash.of(income(Competence.of(2026, 11)));

        assertThat(sem).isEqualTo(CanonicalCommandHash.of(new CreateTransactionCommand.Income(
                new Common(USER_ID, Money.of("1500.00"), ADIANTAMENTO, "Adiantamento", null), nubank.id(), salary.id(), PaymentMethod.PIX)));
        assertThat(List.of(sem, outubro, novembro)).doesNotHaveDuplicates();
    }
}
