package com.moneylog.core.finance.app.export;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.NOW;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.asset.app.gateways.AssetGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.app.export.BackupExceptions.BackupInvalidException;
import com.moneylog.core.finance.app.export.BackupExceptions.BackupSchemaUnsupportedException;
import com.moneylog.core.finance.app.export.BackupExceptions.RestoreTargetNotEmptyException;
import com.moneylog.core.finance.app.export.RestoreBackupUseCase.RestoreBackupCommand;
import com.moneylog.core.finance.app.export.RestoreBackupUseCase.RestoreSummary;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.app.gateways.PayrollLoanGateway;
import com.moneylog.core.finance.app.gateways.PurchaseGroupBackupGateway;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.transaction.app.gateways.ForeignChargeGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;

@DisplayName("Restauração do backup: destino vazio, versões aceitas, referências, ordem das chaves e razão")
class RestoreBackupUseCaseImplTest {

    private final RestoreTargetGateway target = mock(RestoreTargetGateway.class);
    private final CategoryGateway categories = mock(CategoryGateway.class);
    private final SeedSystemCategoriesUseCase seed = mock(SeedSystemCategoriesUseCase.class);
    private final AccountGateway accounts = mock(AccountGateway.class);
    private final CardGateway cards = mock(CardGateway.class);
    private final PersonGateway people = mock(PersonGateway.class);
    private final DebtGateway debts = mock(DebtGateway.class);
    private final RecurrenceGateway recurrences = mock(RecurrenceGateway.class);
    private final TransactionGateway transactions = mock(TransactionGateway.class);
    private final ForeignChargeGateway foreignCharges = mock(ForeignChargeGateway.class);
    private final LedgerWriteGateway ledger = mock(LedgerWriteGateway.class);
    private final GoalGateway goals = mock(GoalGateway.class);
    private final AssetGateway assets = mock(AssetGateway.class);
    private final IncomeProfileGateway incomeProfiles = mock(IncomeProfileGateway.class);
    private final PayrollLoanGateway payrollLoans = mock(PayrollLoanGateway.class);
    private final CommitmentGateway commitments = mock(CommitmentGateway.class);
    private final ThirdPartyExpenseGateway thirdParty = mock(ThirdPartyExpenseGateway.class);
    private final PurchaseGroupBackupGateway purchaseGroups = mock(PurchaseGroupBackupGateway.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final FinanceScenarioFactory s = umCenarioBase();

    private final RestoreBackupUseCaseImpl useCase = new RestoreBackupUseCaseImpl(target, categories, seed, accounts, cards, people, debts,
            recurrences, transactions, foreignCharges, ledger, goals, assets, incomeProfiles, payrollLoans, commitments, thirdParty,
            purchaseGroups, time);

    @BeforeEach
    void setUp() {
        when(time.now()).thenReturn(NOW);
    }

    private BackupSnapshot snapshot(int version) {
        FinancialRecords records = s.records();
        List<Person> people = List.of(PersonTestFactory.umJoao(), PersonTestFactory.umPedro());
        return new BackupSnapshot(version, NOW, records.accounts(), List.of(s.alimentacao, s.salario), records.cards(),
                records.transactions(), records.debts(), people, List.of(), List.of(), records.entries());
    }

    private void verifyNothingWritten() {
        verifyNoInteractions(accounts, cards, people, debts, recurrences, transactions, foreignCharges, ledger, goals, assets,
                incomeProfiles, payrollLoans, commitments, thirdParty, purchaseGroups, seed);
        verify(categories, never()).deleteAllOf(any());
    }

    @Test
    @DisplayName("versão fora de 1 e 2 é recusada sem tocar em nada; 1 e 2 são aceitas")
    void deveAceitarSoAsVersoesConhecidas() {
        s.caso1DespesaPix();

        assertThatThrownBy(() -> useCase.handle(new RestoreBackupCommand(USER_ID, snapshot(3))))
                .isInstanceOf(BackupSchemaUnsupportedException.class).extracting("code").isEqualTo("BACKUP_SCHEMA_UNSUPPORTED");
        verifyNothingWritten();

        RestoreSummary legacy = useCase.handle(new RestoreBackupCommand(USER_ID, snapshot(1)));
        RestoreSummary current = useCase.handle(new RestoreBackupCommand(USER_ID, snapshot(2)));

        assertThat(legacy.schemaVersion()).isEqualTo(1);
        assertThat(current.schemaVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("trava o usuário antes de conferir se o destino está vazio e antes de gravar qualquer coisa")
    void deveTravarOUsuarioAntesDeConferirODestino() {
        s.caso1DespesaPix();

        useCase.handle(new RestoreBackupCommand(USER_ID, snapshot(2)));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(target, categories, accounts);
        order.verify(target).lockUser(USER_ID);
        order.verify(target).hasData(USER_ID);
        order.verify(categories).deleteAllOf(USER_ID);
    }

    @Test
    @DisplayName("conta com dados financeiros: 409 RESTORE_TARGET_NOT_EMPTY e nada é gravado")
    void deveRecusarDestinoComDados() {
        s.caso1DespesaPix();
        when(target.hasData(USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> useCase.handle(new RestoreBackupCommand(USER_ID, snapshot(2))))
                .isInstanceOf(RestoreTargetNotEmptyException.class).extracting("code").isEqualTo("RESTORE_TARGET_NOT_EMPTY");

        verifyNothingWritten();
    }

    @Test
    @DisplayName("referência para uma pessoa que não está no arquivo: BACKUP_INVALID e nada é gravado")
    void deveRecusarReferenciaInexistente() {
        s.caso6Emprestimo();
        BackupSnapshot semPessoas = new BackupSnapshot(2, NOW, s.records().accounts(), List.of(s.alimentacao, s.salario),
                s.records().cards(), s.records().transactions(), s.records().debts(), List.of(), List.of(), List.of(),
                s.records().entries());

        assertThatThrownBy(() -> useCase.handle(new RestoreBackupCommand(USER_ID, semPessoas)))
                .isInstanceOf(BackupInvalidException.class).extracting("code").isEqualTo("BACKUP_INVALID");

        verifyNothingWritten();
    }

    @Test
    @DisplayName("movimentação que aponta para uma conta fora do arquivo: BACKUP_INVALID e nada é gravado")
    void deveRecusarContaInexistente() {
        s.caso1DespesaPix();
        BackupSnapshot semANubank = new BackupSnapshot(2, NOW, List.of(s.xp, s.xpInvest),
                List.of(s.alimentacao, s.salario), List.of(), s.records().transactions(), List.of(), List.of(), List.of(), List.of(),
                s.records().entries());

        assertThatThrownBy(() -> useCase.handle(new RestoreBackupCommand(USER_ID, semANubank)))
                .isInstanceOf(BackupInvalidException.class);
        verifyNothingWritten();
    }

    @Test
    @DisplayName("grava na ordem das chaves (categorias, contas, cartão, pessoas, dívidas, movimentações, razão, sementes) e o razão tem as mesmas partidas do arquivo, todas ativas")
    void deveGravarNaOrdemDasChaves() {
        s.caso1DespesaPix();
        s.caso2CompraParcelada();
        s.caso6Emprestimo();
        s.caso7Devolucao();
        BackupSnapshot snapshot = snapshot(2);

        RestoreSummary summary = useCase.handle(new RestoreBackupCommand(USER_ID, snapshot));

        InOrder order = inOrder(categories, accounts, cards, people, debts, transactions, ledger, seed);
        order.verify(categories).deleteAllOf(USER_ID);
        order.verify(categories, org.mockito.Mockito.times(2)).saveSystem(any(), any());
        order.verify(accounts, org.mockito.Mockito.times(3)).save(any());
        order.verify(cards).save(any());
        order.verify(people, org.mockito.Mockito.times(2)).insert(any());
        order.verify(debts).insert(any());
        order.verify(transactions, org.mockito.Mockito.times(4)).insert(any());
        order.verify(ledger).append(eq(USER_ID), anyList());
        order.verify(seed).handle(any());
        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledger).append(eq(USER_ID), captor.capture());
        assertThat(captor.getValue()).hasSize(s.records().entries().size()).allMatch(LedgerEntry::active);
        assertThat(summary.counts()).containsEntry("transactions", 4).containsEntry("accounts", 3)
                .containsEntry("ledgerPostings", s.records().entries().size());
        verify(ledger, never()).deactivateBySource(any(), any(), any(), any(), any());
        verify(categories).saveSystem(s.alimentacao, "FOOD");
        verify(categories).saveSystem(s.salario, "SALARY");
    }

    @Test
    @DisplayName("movimentação estornada volta estornada: os lançamentos entram e a origem é desativada, sem mexer nas outras")
    void deveRestaurarOEstornoDesativandoAOrigem() {
        Transaction ativa = s.caso1DespesaPix();
        Transaction estornada = s.caso4Transferencia();
        s.estornar(estornada, "lançada por engano", BASE_DATE);

        useCase.handle(new RestoreBackupCommand(USER_ID, snapshot(2)));

        verify(ledger).deactivateBySource(eq(USER_ID), eq(PostingSourceType.TRANSACTION), eq(estornada.id()), any(), eq(NOW));
        verify(ledger, never()).deactivateBySource(eq(USER_ID), any(), eq(ativa.id()), any(), any());
    }

    @Test
    @DisplayName("o estorno de uma compra vem depois da compra original, mesmo que o arquivo traga na ordem inversa")
    void deveInserirOEstornoDepoisDoOriginal() {
        Transaction original = s.caso1DespesaPix();
        Transaction reembolso = s.caso9Reembolso(original);
        FinancialRecords records = s.records();
        List<Transaction> invertidas = new ArrayList<>(List.of(reembolso, original));
        BackupSnapshot snapshot = new BackupSnapshot(2, NOW, records.accounts(), List.of(s.alimentacao, s.salario), records.cards(),
                invertidas, List.of(), List.of(), List.of(), List.of(), records.entries());

        useCase.handle(new RestoreBackupCommand(USER_ID, snapshot));

        InOrder order = inOrder(transactions);
        order.verify(transactions).insert(original);
        order.verify(transactions).insert(reembolso);
    }
}
