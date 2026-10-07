package com.moneylog.core.finance.app.accounts;

import static com.moneylog.core.account.util.factory.AccountTestFactory.NUBANK_ID;
import static com.moneylog.core.account.util.factory.AccountTestFactory.OPENING_DATE;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.finance.app.accounts.usecase.ArchiveAccountUseCase.ArchiveAccountCommand;
import com.moneylog.core.finance.app.accounts.usecase.ArchiveAccountUseCaseImpl;
import com.moneylog.core.finance.app.accounts.usecase.CreateAccountUseCase.CreateAccountCommand;
import com.moneylog.core.finance.app.accounts.usecase.CreateAccountUseCaseImpl;
import com.moneylog.core.finance.app.accounts.usecase.GetAccountUseCase.GetAccountQuery;
import com.moneylog.core.finance.app.accounts.usecase.GetAccountUseCaseImpl;
import com.moneylog.core.finance.app.accounts.usecase.ListAccountsUseCase.ListAccountsQuery;
import com.moneylog.core.finance.app.accounts.usecase.ListAccountsUseCaseImpl;
import com.moneylog.core.finance.app.accounts.usecase.UnarchiveAccountUseCase.UnarchiveAccountCommand;
import com.moneylog.core.finance.app.accounts.usecase.UnarchiveAccountUseCaseImpl;
import com.moneylog.core.finance.app.accounts.usecase.UpdateAccountUseCase.UpdateAccountCommand;
import com.moneylog.core.finance.app.accounts.usecase.UpdateAccountUseCaseImpl;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.account.exception.AccountExceptions.AccountHasReservationsException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.ReserveAllocation;
import com.moneylog.core.account.exception.AccountExceptions.AccountOpeningLockedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountWithBalanceCannotBeArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountWithFutureBalanceCannotBeArchivedException;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;

@ExtendWith(MockitoExtension.class)
@DisplayName("Casos de uso de conta (T25)")
class AccountUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    @Mock
    private AccountGateway accounts;
    @Mock
    private LedgerWriteGateway ledger;
    @Mock
    private FinancialRecordsGateway records;
    @Mock
    private IdGenerator ids;

    private final TimeProvider time = new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));

    private Persisted<Account> persisted(Account account) {
        return new Persisted<>(account, NOW, NOW);
    }

    private FinancialRecords recordsOf(Account... all) {
        return new FinancialRecords(List.of(all), List.of(), List.of(), List.of());
    }

    private Transaction expenseOn(Account account, String amount) {
        return AccountTransactions.expense(TransactionHeader.of(java.util.UUID.fromString("00000000-0000-0000-0000-0000000e0001"),
                        USER_ID, TODAY, "Mercado"), Money.of(amount), account, CategoryTestFactory.umaCategoriaDeDespesa(), PaymentMethod.PIX);
    }

    @Nested
    @DisplayName("criar")
    class Criar {

        @Test
        @DisplayName("grava a conta e o par de abertura, nessa ordem, e devolve saldo e flags do mesmo carregamento")
        void deveCriarComSaldoInicial() {
            when(ids.next()).thenReturn(NUBANK_ID);
            when(accounts.save(any())).thenAnswer(call -> persisted(call.getArgument(0)));
            Account expected = Account.open(NUBANK_ID, USER_ID, "Nubank", AccountType.CHECKING, "Nubank", Money.of("20000.00"), OPENING_DATE);
            when(records.load(USER_ID)).thenReturn(recordsOf(expected));

            AccountDetails details = new CreateAccountUseCaseImpl(accounts, ledger, records, ids, time).handle(
                    new CreateAccountCommand(USER_ID, "Nubank", AccountType.CHECKING, "Nubank", Money.of("20000.00"), OPENING_DATE));

            assertThat(details.currentBalance()).isEqualTo(Money.of("20000.00"));
            assertThat(details.openingEditable()).isTrue();
            assertThat(details.archivable()).isFalse();
            InOrder order = inOrder(accounts, ledger);
            order.verify(accounts).save(any(Account.class));
            ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
            order.verify(ledger).append(eq(USER_ID), entries.capture());
            assertThat(entries.getValue()).hasSize(2).allMatch(e -> e.active() && e.source().equals(PostingSource.accountOpening(NUBANK_ID)));
        }

        @Test
        @DisplayName("saldo inicial zero não grava lançamento")
        void naoDeveGravarLancamentoDeSaldoZero() {
            when(ids.next()).thenReturn(NUBANK_ID);
            when(accounts.save(any())).thenAnswer(call -> persisted(call.getArgument(0)));
            when(records.load(USER_ID)).thenReturn(recordsOf());

            new CreateAccountUseCaseImpl(accounts, ledger, records, ids, time).handle(
                    new CreateAccountCommand(USER_ID, "XP", AccountType.CHECKING, null, Money.of("0.00"), OPENING_DATE));

            verify(ledger, never()).append(any(), any());
        }

        @Test
        @DisplayName("saldo negativo é aceito e aparece negativo (S-5)")
        void deveAceitarSaldoNegativo() {
            when(ids.next()).thenReturn(NUBANK_ID);
            when(accounts.save(any())).thenAnswer(call -> persisted(call.getArgument(0)));
            Account overdraft = Account.open(NUBANK_ID, USER_ID, "Cheque especial", AccountType.CHECKING, null, Money.of("-500.00"), OPENING_DATE);
            when(records.load(USER_ID)).thenReturn(recordsOf(overdraft));

            AccountDetails details = new CreateAccountUseCaseImpl(accounts, ledger, records, ids, time).handle(
                    new CreateAccountCommand(USER_ID, "Cheque especial", AccountType.CHECKING, null, Money.of("-500.00"), OPENING_DATE));

            assertThat(details.currentBalance()).isEqualTo(Money.of("-500.00"));
        }

        @Test
        @DisplayName("saldo inicial com data futura: saldo de hoje é zero")
        void deveTerSaldoZeroAteAData() {
            when(ids.next()).thenReturn(NUBANK_ID);
            when(accounts.save(any())).thenAnswer(call -> persisted(call.getArgument(0)));
            Account future = Account.open(NUBANK_ID, USER_ID, "Nubank", AccountType.CHECKING, null, Money.of("100.00"), LocalDate.of(2026, 10, 20));
            when(records.load(USER_ID)).thenReturn(recordsOf(future));

            AccountDetails details = new CreateAccountUseCaseImpl(accounts, ledger, records, ids, time).handle(
                    new CreateAccountCommand(USER_ID, "Nubank", AccountType.CHECKING, null, Money.of("100.00"), LocalDate.of(2026, 10, 20)));

            assertThat(details.currentBalance()).isEqualTo(Money.of("0.00"));
            assertThat(details.archivable()).isFalse();
        }
    }

    @Nested
    @DisplayName("editar")
    class Editar {

        private UpdateAccountUseCaseImpl useCase() {
            return new UpdateAccountUseCaseImpl(accounts, ledger, records, time);
        }

        @Test
        @DisplayName("sem movimentação: troca o saldo inicial, desativa o par antigo e grava o novo")
        void deveCorrigirOSaldoInicial() {
            Account account = AccountTestFactory.umaContaCorrente();
            when(accounts.findById(USER_ID, NUBANK_ID)).thenReturn(Optional.of(persisted(account)));
            when(records.load(USER_ID)).thenReturn(recordsOf(account));
            when(accounts.save(any())).thenAnswer(call -> persisted(call.getArgument(0)));

            AccountDetails details = useCase().handle(new UpdateAccountCommand(USER_ID, NUBANK_ID, "Nubank PJ", null,
                    AccountType.SAVINGS, Money.of("15000.00"), OPENING_DATE, 0));

            assertThat(details.account().name()).isEqualTo("Nubank PJ");
            assertThat(details.account().openingBalance()).isEqualTo(Money.of("15000.00"));
            InOrder order = inOrder(accounts, ledger);
            order.verify(accounts).save(any(Account.class));
            order.verify(ledger).deactivateBySource(USER_ID, PostingSourceType.ACCOUNT_OPENING, NUBANK_ID, "OPENING_CORRECTED", NOW);
            ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
            order.verify(ledger).append(eq(USER_ID), entries.capture());
            assertThat(entries.getValue()).extracting(e -> e.posting().amount()).containsExactly(Money.of("15000.00"), Money.of("-15000.00"));
        }

        @Test
        @DisplayName("com movimentação ativa: ACCOUNT_OPENING_LOCKED e nada é gravado; só nome e instituição continuam aceitos")
        void deveTravarOSaldoInicialComMovimentacao() {
            Account account = AccountTestFactory.umaContaCorrente();
            when(accounts.findById(USER_ID, NUBANK_ID)).thenReturn(Optional.of(persisted(account)));
            when(records.load(USER_ID)).thenReturn(new FinancialRecords(List.of(account), List.of(), List.of(expenseOn(account, "10.00")), List.of()));

            assertThatThrownBy(() -> useCase().handle(new UpdateAccountCommand(USER_ID, NUBANK_ID, "X", null, null,
                    Money.of("1.00"), null, 0))).isInstanceOf(AccountOpeningLockedException.class);

            verify(accounts, never()).save(any());
            verify(ledger, never()).deactivateBySource(any(), any(), any(), anyString(), any());

            when(accounts.save(any())).thenAnswer(call -> persisted(call.getArgument(0)));
            AccountDetails renamed = useCase().handle(new UpdateAccountCommand(USER_ID, NUBANK_ID, "Só o nome", "Banco", null, null, null, 0));

            assertThat(renamed.account().name()).isEqualTo("Só o nome");
            assertThat(renamed.openingEditable()).isFalse();
            verify(ledger, never()).deactivateBySource(any(), any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("campos do saldo inicial iguais aos atuais não contam como mudança (mesmo com movimentação)")
        void deveIgnorarSaldoInicialInalterado() {
            Account account = AccountTestFactory.umaContaCorrente();
            when(accounts.findById(USER_ID, NUBANK_ID)).thenReturn(Optional.of(persisted(account)));
            when(records.load(USER_ID)).thenReturn(new FinancialRecords(List.of(account), List.of(), List.of(expenseOn(account, "10.00")), List.of()));
            when(accounts.save(any())).thenAnswer(call -> persisted(call.getArgument(0)));

            useCase().handle(new UpdateAccountCommand(USER_ID, NUBANK_ID, "Nubank", null, AccountType.CHECKING,
                    Money.of("20000.00"), OPENING_DATE, 0));

            verify(ledger, never()).deactivateBySource(any(), any(), any(), anyString(), any());
            verify(ledger, never()).append(any(), any());
        }

        @Test
        @DisplayName("versão desatualizada é VERSION_CONFLICT; conta inexistente ou de outro usuário é ACCOUNT_NOT_FOUND")
        void deveRecusarVersaoEInexistente() {
            when(accounts.findById(USER_ID, NUBANK_ID)).thenReturn(Optional.of(persisted(Account.restore(NUBANK_ID, USER_ID, "Nubank",
                    AccountType.CHECKING, null, Money.of("0.00"), OPENING_DATE, AccountStatus.ACTIVE, 4))));

            assertThatThrownBy(() -> useCase().handle(new UpdateAccountCommand(USER_ID, NUBANK_ID, "X", null, null, null, null, 3)))
                    .isInstanceOf(VersionConflictException.class);

            when(accounts.findById(USER_ID, NUBANK_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> useCase().handle(new UpdateAccountCommand(USER_ID, NUBANK_ID, "X", null, null, null, null, 0)))
                    .isInstanceOf(AccountNotFoundException.class);
            verify(accounts, never()).save(any());
        }
    }

    @Nested
    @DisplayName("arquivar e desarquivar")
    class Arquivar {

        @Test
        @DisplayName("saldo zero em todas as datas arquiva; o status gravado é ARCHIVED")
        void deveArquivar() {
            Account account = AccountTestFactory.umaContaSemSaldo();
            when(accounts.findById(USER_ID, account.id())).thenReturn(Optional.of(persisted(account)));
            when(records.load(USER_ID)).thenReturn(recordsOf(account));
            when(accounts.save(any())).thenAnswer(call -> persisted(call.getArgument(0)));

            AccountDetails details = new ArchiveAccountUseCaseImpl(accounts, records, time)
                    .handle(new ArchiveAccountCommand(USER_ID, account.id()));

            assertThat(details.account().status()).isEqualTo(AccountStatus.ARCHIVED);
            assertThat(details.archivable()).isFalse();
        }

        @Test
        @DisplayName("saldo diferente de zero hoje é ACCOUNT_WITH_BALANCE_CANNOT_BE_ARCHIVED e a conta não é gravada")
        void deveRecusarComSaldo() {
            Account account = AccountTestFactory.umaContaCorrente();
            when(accounts.findById(USER_ID, NUBANK_ID)).thenReturn(Optional.of(persisted(account)));
            when(records.load(USER_ID)).thenReturn(recordsOf(account));

            assertThatThrownBy(() -> new ArchiveAccountUseCaseImpl(accounts, records, time).handle(new ArchiveAccountCommand(USER_ID, NUBANK_ID)))
                    .isInstanceOf(AccountWithBalanceCannotBeArchivedException.class);

            verify(accounts, never()).save(any());
        }

        @Test
        @DisplayName("S-259 (A1): reserva ativa positiva de meta (inclusive com data futura) é ACCOUNT_HAS_RESERVATIONS e a conta não é gravada; reserva liberada ou meta arquivada não impedem")
        void deveRecusarComReservaDeMeta() {
            Account account = AccountTestFactory.umaContaSemSaldo();
            when(accounts.findById(USER_ID, account.id())).thenReturn(Optional.of(persisted(account)));
            when(accounts.save(any())).thenAnswer(call -> persisted(call.getArgument(0)));
            Goal goal = com.moneylog.core.goal.util.GoalTestFactory.umaMetaDeEmergencia();
            Instant now = Instant.parse("2026-10-10T15:00:00Z");
            goal.reserve(java.util.UUID.randomUUID(), ReserveAllocation.of(account, Money.of("100.00")), LocalDate.of(2026, 12, 1), null, now);
            when(records.load(USER_ID)).thenReturn(new FinancialRecords(List.of(account), List.of(), List.of(), List.of(), List.of(goal)));

            assertThatThrownBy(() -> new ArchiveAccountUseCaseImpl(accounts, records, time).handle(new ArchiveAccountCommand(USER_ID, account.id())))
                    .isInstanceOf(AccountHasReservationsException.class).hasMessageContaining("Reserva de emergência");
            verify(accounts, never()).save(any());

            goal.reserve(java.util.UUID.randomUUID(), ReserveAllocation.of(account, Money.of("-100.00")), LocalDate.of(2026, 12, 2), null, now);
            Account fresh = AccountTestFactory.umaContaSemSaldo();
            when(accounts.findById(USER_ID, fresh.id())).thenReturn(Optional.of(persisted(fresh)));
            assertThat(new ArchiveAccountUseCaseImpl(accounts, records, time).handle(new ArchiveAccountCommand(USER_ID, fresh.id())).account().status())
                    .isEqualTo(AccountStatus.ARCHIVED);
        }

        @Test
        @DisplayName("reserva numa meta arquivada não reserva nada: a conta arquiva")
        void metaArquivadaNaoImpede() {
            Account account = AccountTestFactory.umaContaSemSaldo();
            when(accounts.findById(USER_ID, account.id())).thenReturn(Optional.of(persisted(account)));
            when(accounts.save(any())).thenAnswer(call -> persisted(call.getArgument(0)));
            Goal goal = com.moneylog.core.goal.util.GoalTestFactory.umaMetaDeEmergencia();
            goal.reserve(java.util.UUID.randomUUID(), ReserveAllocation.of(account, Money.of("100.00")), LocalDate.of(2026, 10, 10), null,
                    Instant.parse("2026-10-10T15:00:00Z"));
            goal.archive();
            when(records.load(USER_ID)).thenReturn(new FinancialRecords(List.of(account), List.of(), List.of(), List.of(), List.of(goal)));

            assertThat(new ArchiveAccountUseCaseImpl(accounts, records, time).handle(new ArchiveAccountCommand(USER_ID, account.id())).account().status())
                    .isEqualTo(AccountStatus.ARCHIVED);
        }

        @Test
        @DisplayName("saldo zero hoje mas saldo futuro (S-92) é a variante FUTURE")
        void deveRecusarComSaldoFuturo() {
            Account account = Account.open(NUBANK_ID, USER_ID, "Nubank", AccountType.CHECKING, null, Money.of("100.00"), LocalDate.of(2026, 10, 20));
            when(accounts.findById(USER_ID, NUBANK_ID)).thenReturn(Optional.of(persisted(account)));
            when(records.load(USER_ID)).thenReturn(recordsOf(account));

            assertThatThrownBy(() -> new ArchiveAccountUseCaseImpl(accounts, records, time).handle(new ArchiveAccountCommand(USER_ID, NUBANK_ID)))
                    .isInstanceOf(AccountWithFutureBalanceCannotBeArchivedException.class);
        }

        @Test
        @DisplayName("arquivar inexistente é 404; desarquivar volta para ACTIVE; desarquivar uma ativa é 422")
        void deveDesarquivar() {
            when(accounts.findById(USER_ID, NUBANK_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> new ArchiveAccountUseCaseImpl(accounts, records, time).handle(new ArchiveAccountCommand(USER_ID, NUBANK_ID)))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThatThrownBy(() -> new UnarchiveAccountUseCaseImpl(accounts, records, time).handle(new UnarchiveAccountCommand(USER_ID, NUBANK_ID)))
                    .isInstanceOf(AccountNotFoundException.class);

            Account archived = AccountTestFactory.umaContaArquivada();
            when(accounts.findById(USER_ID, archived.id())).thenReturn(Optional.of(persisted(archived)));
            when(accounts.save(any())).thenAnswer(call -> persisted(call.getArgument(0)));
            when(records.load(USER_ID)).thenReturn(recordsOf(archived));

            AccountDetails details = new UnarchiveAccountUseCaseImpl(accounts, records, time).handle(new UnarchiveAccountCommand(USER_ID, archived.id()));

            assertThat(details.account().status()).isEqualTo(AccountStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("consultar")
    class Consultar {

        @Test
        @DisplayName("get devolve saldo e flags; inexistente é 404")
        void deveConsultar() {
            Account account = AccountTestFactory.umaContaCorrente();
            when(accounts.findById(USER_ID, NUBANK_ID)).thenReturn(Optional.of(persisted(account)), Optional.empty());
            when(records.load(USER_ID)).thenReturn(recordsOf(account));
            GetAccountUseCaseImpl useCase = new GetAccountUseCaseImpl(accounts, records, time);

            assertThat(useCase.handle(new GetAccountQuery(USER_ID, NUBANK_ID)).currentBalance()).isEqualTo(Money.of("20000.00"));
            assertThatThrownBy(() -> useCase.handle(new GetAccountQuery(USER_ID, NUBANK_ID))).isInstanceOf(AccountNotFoundException.class);
        }

        @Test
        @DisplayName("a lista calcula todos os saldos com um único carregamento e preserva hasNext")
        void deveListarComUmCarregamento() {
            Account nubank = AccountTestFactory.umaContaCorrente();
            Account xp = AccountTestFactory.umaContaSemSaldo();
            when(accounts.list(USER_ID, null, new PageQuery(1, 1))).thenReturn(new Slice<>(List.of(persisted(nubank)), true));
            when(accounts.list(USER_ID, AccountStatus.ARCHIVED, new PageQuery(1, 10))).thenReturn(Slice.empty());
            when(records.load(USER_ID)).thenReturn(recordsOf(nubank, xp));
            ListAccountsUseCaseImpl useCase = new ListAccountsUseCaseImpl(accounts, records, time);

            Slice<AccountDetails> page = useCase.handle(new ListAccountsQuery(USER_ID, null, new PageQuery(1, 1)));

            assertThat(page.hasNext()).isTrue();
            assertThat(page.items()).hasSize(1).first().satisfies(item -> assertThat(item.currentBalance()).isEqualTo(Money.of("20000.00")));
            assertThat(useCase.handle(new ListAccountsQuery(USER_ID, AccountStatus.ARCHIVED, PageQuery.first()))).isEqualTo(Slice.empty());
            verify(records, org.mockito.Mockito.times(1)).load(USER_ID);
        }
    }

    @Test
    @DisplayName("movimentação estornada não conta como movimentação ativa; ativa que usa a conta como contrapartida conta")
    void deveContarSoMovimentacaoAtiva() {
        Account nubank = AccountTestFactory.umaContaCorrente();
        Account xp = AccountTestFactory.umaContaSemSaldo();
        Transaction transfer = AccountTransactions.transfer(TransactionHeader.of(java.util.UUID.fromString("00000000-0000-0000-0000-0000000e0002"),
                USER_ID, TODAY, "Transferência"), Money.of("10.00"), nubank, xp);
        FinancialRecords active = new FinancialRecords(List.of(nubank, xp), List.of(), List.of(transfer), List.of());

        assertThat(AccountViews.hasActiveMovements(active, xp.id())).isTrue();
        assertThat(AccountViews.hasActiveMovements(active, nubank.id())).isTrue();
        assertThat(AccountViews.hasActiveMovements(active, java.util.UUID.randomUUID())).isFalse();

        com.moneylog.core.transaction.domain.TransactionVoiding.voidTransaction(transfer,
                com.moneylog.core.transaction.domain.VoidContext.of("engano", NOW, TODAY).withAccounts(List.of(nubank, xp)));
        FinancialRecords voided = new FinancialRecords(List.of(nubank, xp), List.of(), List.of(transfer), List.of());
        assertThat(AccountViews.hasActiveMovements(voided, xp.id())).isFalse();
    }
}
