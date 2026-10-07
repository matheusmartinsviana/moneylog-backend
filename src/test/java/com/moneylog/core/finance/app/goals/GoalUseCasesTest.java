package com.moneylog.core.finance.app.goals;

import static com.moneylog.core.account.util.factory.AccountTestFactory.NUBANK_ID;
import static com.moneylog.core.goal.util.GoalTestFactory.DEADLINE;
import static com.moneylog.core.goal.util.GoalTestFactory.GOAL_ID;
import static com.moneylog.core.goal.util.GoalTestFactory.NOW;
import static com.moneylog.core.goal.util.GoalTestFactory.TODAY;
import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.exception.AccountExceptions.AccountArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.usecase.ArchiveGoalUseCase.ArchiveGoalCommand;
import com.moneylog.core.finance.app.goals.usecase.ArchiveGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.CreateGoalUseCase.CreateGoalCommand;
import com.moneylog.core.finance.app.goals.usecase.CreateGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.GetGoalUseCase.GetGoalQuery;
import com.moneylog.core.finance.app.goals.usecase.GetGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.ListGoalsUseCase.ListGoalsQuery;
import com.moneylog.core.finance.app.goals.usecase.ListGoalsUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.ReserveOnGoalUseCase;
import com.moneylog.core.finance.app.goals.usecase.ReserveOnGoalUseCase.ReserveOnGoalCommand;
import com.moneylog.core.finance.app.goals.usecase.ReserveOnGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.UnarchiveGoalUseCase.UnarchiveGoalCommand;
import com.moneylog.core.finance.app.goals.usecase.UnarchiveGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.UpdateGoalUseCase.UpdateGoalCommand;
import com.moneylog.core.finance.app.goals.usecase.UpdateGoalUseCaseImpl;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.goal.domain.GoalStatus;
import com.moneylog.core.goal.exception.GoalExceptions.GoalAlreadyArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalReleaseExceedsReservedException;
import com.moneylog.core.goal.util.InMemoryGoalGateway;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;

@DisplayName("Casos de uso de metas (T93, S-147 a S-150): reserva lógica, travas, idempotência e status derivado")
class GoalUseCasesTest {

    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final InMemoryGoalGateway goals = new InMemoryGoalGateway(NOW);
    private final AccountGateway accounts = mock(AccountGateway.class);
    private final FinancialRecordsGateway records = mock(FinancialRecordsGateway.class);
    private final IdempotencyGateway idempotency = mock(IdempotencyGateway.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();

    private CreateGoalUseCaseImpl create;
    private UpdateGoalUseCaseImpl update;
    private GetGoalUseCaseImpl get;
    private ListGoalsUseCaseImpl list;
    private ReserveOnGoalUseCaseImpl reserve;
    private ArchiveGoalUseCaseImpl archive;
    private UnarchiveGoalUseCaseImpl unarchive;

    @BeforeEach
    void setUp() {
        when(time.today()).thenReturn(TODAY);
        when(time.now()).thenReturn(NOW);
        when(ids.next()).thenReturn(GOAL_ID, UUID.fromString("00000000-0000-0000-0000-0000000000b1"), UUID.fromString("00000000-0000-0000-0000-0000000000b2"),
                UUID.fromString("00000000-0000-0000-0000-0000000000b3"));
        // as metas do carregamento único são as da porta em memória (no sistema real, o FinancialRecordsGateway as lê do banco)
        when(records.load(any())).thenAnswer(invocation -> {
            FinancialRecords base = scenario.records();
            return new FinancialRecords(base.accounts(), base.cards(), base.transactions(), base.debts(),
                    goals.findAll(invocation.getArgument(0)).stream().map(Versioned::value).toList());
        });
        when(accounts.findById(USER_ID, NUBANK_ID)).thenReturn(Optional.of(new Persisted<>(scenario.nubank, NOW, NOW)));
        when(idempotency.claim(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(true);
        create = new CreateGoalUseCaseImpl(goals, records, idempotency, ids, time);
        update = new UpdateGoalUseCaseImpl(goals, records, time);
        get = new GetGoalUseCaseImpl(goals, records, time);
        list = new ListGoalsUseCaseImpl(goals, records, time);
        reserve = new ReserveOnGoalUseCaseImpl(goals, accounts, records, idempotency, ids, time);
        archive = new ArchiveGoalUseCaseImpl(goals, records, time);
        unarchive = new UnarchiveGoalUseCaseImpl(goals, records, time);
    }

    private GoalDetails novaMeta(String name, String target, LocalDate deadline, GoalPriority priority) {
        when(ids.next()).thenReturn(UUID.randomUUID());
        return create.handle(new CreateGoalCommand(USER_ID, name, Money.of(target), deadline, priority, null, null));
    }

    private GoalDetails emergencia() {
        return create.handle(new CreateGoalCommand(USER_ID, "Reserva de emergência", Money.of("20000.00"), DEADLINE, GoalPriority.HIGH, "Seis meses", null));
    }

    private GoalDetails reservar(UUID goalId, String amount) {
        return reserve.handle(new ReserveOnGoalCommand(USER_ID, goalId, NUBANK_ID, Money.of(amount), TODAY, null, UUID.randomUUID()));
    }

    @Test
    @DisplayName("CA1: cria a meta (current 0,00, progress 0, monthlyNeeded do backend) e reserva +8.500,00: devolve a meta com a reserva, current 8.500,00 e 766,67 por mês")
    void deveCriarEReservar() {
        GoalDetails created = emergencia();

        assertThat(created.goal().id()).isEqualTo(GOAL_ID);
        assertThat(created.current()).isEqualTo(Money.of("0.00"));
        assertThat(created.progress().ratioText()).isEqualTo("0.0000");
        assertThat(created.status()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(created.version()).isZero();
        assertThat(created.reservations()).isEmpty();
        assertThat(created.monthlyNeeded()).isEqualTo(Money.of("1333.34"));

        GoalDetails reserved = reservar(GOAL_ID, "8500.00");

        assertThat(reserved.current()).isEqualTo(Money.of("8500.00"));
        assertThat(reserved.progress().ratioText()).isEqualTo("0.4250");
        assertThat(reserved.monthlyNeeded()).isEqualTo(Money.of("766.67"));
        assertThat(reserved.reservations()).singleElement().satisfies(r -> {
            assertThat(r.accountName()).isEqualTo("Nubank");
            assertThat(r.reservation().amount()).isEqualTo(Money.of("8500.00"));
            assertThat(r.reservation().date()).isEqualTo(TODAY);
        });
        assertThat(reserved.version()).isEqualTo(1);
        assertThat(reserved.uncovered()).isFalse();
        assertThat(get.handle(new GetGoalQuery(USER_ID, GOAL_ID)).current()).isEqualTo(Money.of("8500.00"));
    }

    @Test
    @DisplayName("prioridade ausente: MEDIUM")
    void deveAssumirPrioridadeMedia() {
        assertThat(novaMeta("Viagem", "3000.00", null, null).goal().priority()).isEqualTo(GoalPriority.MEDIUM);
    }

    @Test
    @DisplayName("CA2: reservar não grava lançamento nem movimentação: o caso de uso só fala com a porta de metas, a de contas (leitura e trava) e a de idempotência")
    void naoDeveTocarORazao() {
        emergencia();

        reservar(GOAL_ID, "8500.00");

        verify(accounts).lockForUpdate(USER_ID, NUBANK_ID);
        verify(accounts, never()).save(any());
        assertThat(records.load(USER_ID).entries()).isEqualTo(scenario.records().entries());
        assertThat(records.load(USER_ID).transactions()).isEmpty();
    }

    @Test
    @DisplayName("ordem de travas: a meta é travada antes de ser lida, e a conta depois da meta; a chave de idempotência é reivindicada antes de tudo")
    void deveTravarAMetaAntesDasContas() {
        emergencia();
        InOrder order = inOrder(idempotency, accounts);

        reservar(GOAL_ID, "100.00");

        assertThat(goals.calls()).containsSubsequence("save", "lock", "save");
        order.verify(idempotency).claim(eq(USER_ID), eq(ReserveOnGoalUseCase.IDEMPOTENCY_SCOPE), any(), anyString(), any(), any(), any());
        order.verify(accounts).lockForUpdate(USER_ID, NUBANK_ID);
    }

    @Test
    @DisplayName("A2 (S-252): o registro único das travas mostra meta ANTES da conta, tanto na reserva quanto na liberação; inverter a ordem quebra este teste (deadlock futuro)")
    void deveRegistrarAOrdemMetaAntesDaConta() {
        emergencia();
        reservar(GOAL_ID, "300.00");
        goals.calls().clear();
        org.mockito.Mockito.doAnswer(invocation -> goals.calls().add("lock-account")).when(accounts).lockForUpdate(USER_ID, NUBANK_ID);

        reservar(GOAL_ID, "100.00");
        reservar(GOAL_ID, "-50.00");

        assertThat(goals.calls().stream().filter(c -> c.equals("lock") || c.equals("lock-account")).toList())
                .as("uma trava de meta seguida de uma trava de conta, por operação, nunca o contrário")
                .containsExactly("lock", "lock-account", "lock", "lock-account");
    }

    @Test
    @DisplayName("CA3: Idempotency-Key repetida com o mesmo corpo devolve a meta sem reservar de novo; com outro corpo é IDEMPOTENCY_KEY_REUSED")
    void deveSerIdempotente() {
        emergencia();
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        GoalDetails first = reserve.handle(new ReserveOnGoalCommand(USER_ID, GOAL_ID, NUBANK_ID, Money.of("500.00"), TODAY, "n", KEY));
        verify(idempotency).claim(eq(USER_ID), eq(ReserveOnGoalUseCase.IDEMPOTENCY_SCOPE), eq(KEY), hash.capture(), any(), any(), any());
        when(idempotency.claim(eq(USER_ID), anyString(), eq(KEY), anyString(), any(), any(), any())).thenReturn(false);
        when(idempotency.find(USER_ID, ReserveOnGoalUseCase.IDEMPOTENCY_SCOPE, KEY)).thenReturn(Optional.of(new Claim(hash.getValue(), UUID.randomUUID(), NOW)));

        GoalDetails replay = reserve.handle(new ReserveOnGoalCommand(USER_ID, GOAL_ID, NUBANK_ID, Money.of("500.00"), TODAY, "n", KEY));

        assertThat(replay.current()).isEqualTo(first.current()).isEqualTo(Money.of("500.00"));
        assertThat(replay.reservations()).hasSize(1);
        assertThatThrownBy(() -> reserve.handle(new ReserveOnGoalCommand(USER_ID, GOAL_ID, NUBANK_ID, Money.of("501.00"), TODAY, "n", KEY)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(get.handle(new GetGoalQuery(USER_ID, GOAL_ID)).reservations()).as("uma só reserva").hasSize(1);
    }

    @Test
    @DisplayName("a criação também honra a Idempotency-Key opcional: replay devolve a meta atual e outro corpo é recusado")
    void deveCriarComIdempotencia() {
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        CreateGoalCommand command = new CreateGoalCommand(USER_ID, "Viagem", Money.of("3000.00"), null, GoalPriority.LOW, null, KEY);
        create.handle(command);
        verify(idempotency).claim(eq(USER_ID), eq(CreateGoalUseCaseImpl.IDEMPOTENCY_SCOPE), eq(KEY), hash.capture(), eq(GOAL_ID), any(), any());
        when(idempotency.claim(eq(USER_ID), anyString(), eq(KEY), anyString(), any(), any(), any())).thenReturn(false);
        when(idempotency.find(USER_ID, CreateGoalUseCaseImpl.IDEMPOTENCY_SCOPE, KEY)).thenReturn(Optional.of(new Claim(hash.getValue(), GOAL_ID, NOW)));

        GoalDetails replay = create.handle(command);

        assertThat(replay.goal().id()).isEqualTo(GOAL_ID);
        assertThat(goals.saves()).isEqualTo(1);
        assertThatThrownBy(() -> create.handle(new CreateGoalCommand(USER_ID, "Viagem 2", Money.of("3000.00"), null, GoalPriority.LOW, null, KEY)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    @DisplayName("CA4: conta arquivada → ACCOUNT_ARCHIVED (reserva) mas libera; conta de outro usuário ou inexistente → ACCOUNT_NOT_FOUND; meta de outro usuário → GOAL_NOT_FOUND")
    void deveIsolarERecusar() {
        emergencia();
        reservar(GOAL_ID, "100.00");
        when(accounts.findById(USER_ID, NUBANK_ID)).thenReturn(Optional.of(new Persisted<>(archivedNubank(), NOW, NOW)));

        assertThatThrownBy(() -> reservar(GOAL_ID, "1.00")).isInstanceOf(AccountArchivedException.class);
        assertThat(reservar(GOAL_ID, "-100.00").current()).as("a liberação aceita conta arquivada").isEqualTo(Money.of("0.00"));
        when(accounts.findById(USER_ID, NUBANK_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reservar(GOAL_ID, "1.00")).isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> get.handle(new GetGoalQuery(OTHER_USER_ID, GOAL_ID))).isInstanceOf(GoalNotFoundException.class);
        assertThatThrownBy(() -> reserve.handle(new ReserveOnGoalCommand(OTHER_USER_ID, GOAL_ID, NUBANK_ID, Money.of("1.00"), TODAY, null, UUID.randomUUID())))
                .isInstanceOf(GoalNotFoundException.class);
        assertThatThrownBy(() -> archive.handle(new ArchiveGoalCommand(OTHER_USER_ID, GOAL_ID))).isInstanceOf(GoalNotFoundException.class);
        assertThatThrownBy(() -> get.handle(new GetGoalQuery(USER_ID, UUID.randomUUID()))).isInstanceOf(GoalNotFoundException.class);
    }

    private com.moneylog.core.account.domain.Account archivedNubank() {
        return com.moneylog.core.account.domain.Account.restore(NUBANK_ID, USER_ID, "Nubank", com.moneylog.core.account.domain.AccountType.CHECKING, "Nubank",
                Money.of("20000.00"), LocalDate.of(2026, 10, 1), com.moneylog.core.account.domain.AccountStatus.ARCHIVED, 2L);
    }

    @Test
    @DisplayName("liberar mais do que o reservado → GOAL_RELEASE_EXCEEDS_RESERVED, e nada é gravado")
    void deveRecusarLiberacaoAcimaDoReservado() {
        emergencia();
        reservar(GOAL_ID, "100.00");
        int savesBefore = goals.saves();

        assertThatThrownBy(() -> reservar(GOAL_ID, "-100.01")).isInstanceOf(GoalReleaseExceedsReservedException.class);

        assertThat(goals.saves()).isEqualTo(savesBefore);
        assertThat(get.handle(new GetGoalQuery(USER_ID, GOAL_ID)).current()).isEqualTo(Money.of("100.00"));
    }

    @Test
    @DisplayName("reserva acima do saldo é aceita e a meta sai com uncovered = true (S-148)")
    void deveAceitarReservaDescoberta() {
        emergencia();

        GoalDetails details = reservar(GOAL_ID, "25000.00");

        assertThat(details.current()).isEqualTo(Money.of("25000.00"));
        assertThat(details.uncovered()).isTrue();
        assertThat(details.status()).isEqualTo(GoalStatus.ACHIEVED);
    }

    @Test
    @DisplayName("CA6: PUT com a version atual edita; com version velha → VERSION_CONFLICT; meta arquivada → GOAL_ARCHIVED")
    void deveEditarComVersao() {
        emergencia();

        GoalDetails updated = update.handle(new UpdateGoalCommand(USER_ID, GOAL_ID, "Viagem", Money.of("5000.00"), null, GoalPriority.LOW, "Europa", 0));

        assertThat(updated.goal().name()).isEqualTo("Viagem");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.monthlyNeeded()).isNull();
        assertThatThrownBy(() -> update.handle(new UpdateGoalCommand(USER_ID, GOAL_ID, "X", Money.of("1.00"), null, GoalPriority.LOW, null, 0)))
                .isInstanceOf(VersionConflictException.class);
        archive.handle(new ArchiveGoalCommand(USER_ID, GOAL_ID));
        assertThatThrownBy(() -> update.handle(new UpdateGoalCommand(USER_ID, GOAL_ID, "X", Money.of("1.00"), null, GoalPriority.LOW, null, 2)))
                .isInstanceOf(GoalArchivedException.class);
        verify(accounts, never()).lockForUpdate(any(), any());
    }

    @Test
    @DisplayName("arquivar e desarquivar devolvem a meta; arquivada recusa reserva; arquivar de novo é GOAL_ALREADY_ARCHIVED")
    void deveArquivarEDesarquivar() {
        emergencia();

        GoalDetails archived = archive.handle(new ArchiveGoalCommand(USER_ID, GOAL_ID));

        assertThat(archived.status()).isEqualTo(GoalStatus.ARCHIVED);
        assertThatThrownBy(() -> archive.handle(new ArchiveGoalCommand(USER_ID, GOAL_ID))).isInstanceOf(GoalAlreadyArchivedException.class);
        assertThatThrownBy(() -> reservar(GOAL_ID, "1.00")).isInstanceOf(GoalArchivedException.class);
        assertThat(unarchive.handle(new UnarchiveGoalCommand(USER_ID, GOAL_ID)).status()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(reservar(GOAL_ID, "1.00").current()).isEqualTo(Money.of("1.00"));
    }

    @Test
    @DisplayName("CA5: a lista filtra pelo status derivado do domínio (ACTIVE, ACHIEVED, ARCHIVED), ordena e pagina")
    void deveListarPeloStatusDerivado() {
        emergencia();
        GoalDetails second = novaMeta("Curta", "100.00", null, GoalPriority.LOW);
        GoalDetails third = novaMeta("Arquivada", "100.00", null, GoalPriority.MEDIUM);
        reservar(second.goal().id(), "100.00");
        archive.handle(new ArchiveGoalCommand(USER_ID, third.goal().id()));

        assertThat(list.handle(new ListGoalsQuery(USER_ID, GoalStatus.ACHIEVED, PageQuery.first())).items()).extracting(d -> d.goal().name()).containsExactly("Curta");
        assertThat(list.handle(new ListGoalsQuery(USER_ID, GoalStatus.ACTIVE, PageQuery.first())).items()).extracting(d -> d.goal().name()).containsExactly("Reserva de emergência");
        assertThat(list.handle(new ListGoalsQuery(USER_ID, GoalStatus.ARCHIVED, PageQuery.first())).items()).extracting(d -> d.goal().name()).containsExactly("Arquivada");
        assertThat(list.handle(new ListGoalsQuery(USER_ID, null, PageQuery.first())).items()).extracting(d -> d.goal().name())
                .containsExactly("Reserva de emergência", "Curta", "Arquivada");
        assertThat(list.handle(new ListGoalsQuery(OTHER_USER_ID, null, PageQuery.first())).items()).isEmpty();
        var firstPage = list.handle(new ListGoalsQuery(USER_ID, null, new PageQuery(1, 2)));
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.hasNext()).isTrue();
        var lastPage = list.handle(new ListGoalsQuery(USER_ID, null, new PageQuery(2, 2)));
        assertThat(lastPage.items()).extracting(d -> d.goal().name()).containsExactly("Arquivada");
        assertThat(lastPage.hasNext()).isFalse();
        assertThat(list.handle(new ListGoalsQuery(USER_ID, null, new PageQuery(Integer.MAX_VALUE, 50))).items()).isEmpty();
    }

    @Test
    @DisplayName("a ordem da lista dentro do status: prioridade, depois prazo (sem prazo por último) e nome")
    void deveOrdenarPorPrioridadePrazoENome() {
        novaMeta("B baixa", "10.00", null, GoalPriority.LOW);
        novaMeta("A alta sem prazo", "10.00", null, GoalPriority.HIGH);
        novaMeta("C alta com prazo", "10.00", LocalDate.of(2027, 1, 31), GoalPriority.HIGH);
        novaMeta("D media", "10.00", null, GoalPriority.MEDIUM);

        assertThat(list.handle(new ListGoalsQuery(USER_ID, null, PageQuery.first())).items()).extracting(d -> d.goal().name())
                .containsExactly("C alta com prazo", "A alta sem prazo", "D media", "B baixa");
    }

    @Test
    @DisplayName("a lista e o detalhe concordam no reservado e na reserva descoberta (mesmo cálculo)")
    void deveConcordarListaEDetalhe() {
        novaMeta("Grande", "30000.00", null, GoalPriority.HIGH);
        UUID id = list.handle(new ListGoalsQuery(USER_ID, null, PageQuery.first())).items().getFirst().goal().id();
        reservar(id, "25000.00");

        GoalDetails fromList = list.handle(new ListGoalsQuery(USER_ID, null, PageQuery.first())).items().getFirst();
        GoalDetails fromGet = get.handle(new GetGoalQuery(USER_ID, id));

        assertThat(fromList.uncovered()).isTrue().isEqualTo(fromGet.uncovered());
        assertThat(fromList.current()).isEqualTo(fromGet.current());
        assertThat(fromList.progress()).isEqualTo(fromGet.progress());
    }
}
