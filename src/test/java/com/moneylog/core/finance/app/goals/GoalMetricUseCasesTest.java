package com.moneylog.core.finance.app.goals;

import static com.moneylog.core.goal.util.GoalTestFactory.NOW;
import static com.moneylog.core.goal.util.GoalTestFactory.TODAY;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.usecase.AnchorDebtPayoffBaselineUseCase.AnchorDebtPayoffBaselineCommand;
import com.moneylog.core.finance.app.goals.usecase.AnchorDebtPayoffBaselineUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.ArchiveGoalUseCase.ArchiveGoalCommand;
import com.moneylog.core.finance.app.goals.usecase.ArchiveGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.CreateGoalUseCase.CreateGoalCommand;
import com.moneylog.core.finance.app.goals.usecase.CreateGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.GetGoalUseCase.GetGoalQuery;
import com.moneylog.core.finance.app.goals.usecase.GetGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.AddGoalActionUseCase.AddGoalActionCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.CheckInGoalActionUseCase.CheckInGoalActionCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCasesImpl;
import com.moneylog.core.finance.app.goals.usecase.ListGoalsUseCase.ListGoalsQuery;
import com.moneylog.core.finance.app.goals.usecase.ListGoalsUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.RecordGoalSnapshotsUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.UnarchiveGoalUseCase.UnarchiveGoalCommand;
import com.moneylog.core.finance.app.goals.usecase.UnarchiveGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.UpdateGoalUseCase.UpdateGoalCommand;
import com.moneylog.core.finance.app.goals.usecase.UpdateGoalUseCaseImpl;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.DebtOverviewCalculator;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.goal.app.gateways.GoalSnapshotGateway;
import com.moneylog.core.goal.domain.GoalActionFrequency;
import com.moneylog.core.goal.domain.GoalDirection;
import com.moneylog.core.goal.domain.GoalKind;
import com.moneylog.core.goal.domain.GoalMetric;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.goal.domain.GoalStage;
import com.moneylog.core.goal.domain.GoalStatus;
import com.moneylog.core.goal.domain.GoalSnapshot;
import com.moneylog.core.goal.exception.GoalExceptions.GoalFieldRequiredException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalKindNotReservableException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalMetricDuplicateException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalMetricTargetEqualsBaselineException;
import com.moneylog.core.goal.util.InMemoryGoalGateway;
import com.moneylog.core.recurrence.domain.MonthlyEstimate;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;

@DisplayName("Metas de medida e plano (ADR-016): casos de uso, uma leitura por requisição e fontes únicas")
class GoalMetricUseCasesTest {

    private final InMemoryGoalGateway goals = new InMemoryGoalGateway(NOW);
    private final FinancialRecordsGateway records = mock(FinancialRecordsGateway.class);
    private final CommitmentSourcesGateway sources = mock(CommitmentSourcesGateway.class);
    private final GoalSnapshotGateway snapshots = mock(GoalSnapshotGateway.class);
    private final IdempotencyGateway idempotency = mock(IdempotencyGateway.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();

    private GoalReadModel model;
    private CreateGoalUseCaseImpl create;
    private UpdateGoalUseCaseImpl update;
    private GetGoalUseCaseImpl get;
    private ListGoalsUseCaseImpl list;
    private ArchiveGoalUseCaseImpl archive;
    private UnarchiveGoalUseCaseImpl unarchive;
    private AnchorDebtPayoffBaselineUseCaseImpl anchor;
    private GoalActionUseCasesImpl actions;

    @BeforeEach
    void setUp() {
        when(time.today()).thenReturn(TODAY);
        when(time.now()).thenReturn(NOW);
        when(time.zone()).thenReturn(java.time.ZoneId.of("America/Sao_Paulo"));
        when(ids.next()).thenAnswer(i -> UUID.randomUUID());
        scenario.compraNoCartao("1200.00", 6);
        scenario.caso8DividaSemMovimentacao();
        when(records.load(any())).thenAnswer(invocation -> {
            FinancialRecords base = scenario.records();
            return new FinancialRecords(base.accounts(), base.cards(), base.transactions(), base.debts(),
                    goals.findAll(invocation.getArgument(0)).stream().map(Versioned::value).toList());
        });
        when(sources.recurrences(any())).thenReturn(List.of());
        when(idempotency.claim(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        model = new GoalReadModel(sources, snapshots, time);
        create = new CreateGoalUseCaseImpl(goals, records, idempotency, ids, time, model);
        update = new UpdateGoalUseCaseImpl(goals, records, time, model);
        get = new GetGoalUseCaseImpl(goals, records, time, model);
        list = new ListGoalsUseCaseImpl(goals, records, time, model);
        archive = new ArchiveGoalUseCaseImpl(goals, records, time, model);
        unarchive = new UnarchiveGoalUseCaseImpl(goals, records, time, model);
        anchor = new AnchorDebtPayoffBaselineUseCaseImpl(goals, records, time, model);
        actions = new GoalActionUseCasesImpl(goals, records, ids, time, model);
    }

    private GoalDetails metric(GoalMetric metric, String target, LocalDate deadline) {
        return create.handle(new CreateGoalCommand(USER_ID, null, Money.of(target), deadline, null, null, null, GoalKind.METRIC, metric));
    }

    private Money source(GoalMetric metric) {
        FinancialRecords r = records.load(USER_ID);
        return switch (metric) {
            case TOTAL_DEBT -> DebtOverviewCalculator.total(r, TODAY);
            case CARD_DEBT -> DebtOverviewCalculator.parts(r, TODAY).cards();
            case PEOPLE_DEBT -> DebtOverviewCalculator.parts(r, TODAY).people();
            case OTHER_DEBT -> DebtOverviewCalculator.parts(r, TODAY).other();
            case ACCOUNTS_BALANCE -> DashboardSummary.of(r, List.of(), TODAY, TODAY).balances().inAccounts();
            case AVAILABLE_BALANCE -> DashboardSummary.of(r, List.of(), TODAY, TODAY).balances().available();
            case FIXED_COSTS_MONTHLY -> MonthlyEstimate.of(List.of(), TODAY).fixedCosts();
        };
    }

    @Test
    @DisplayName("criar METRIC captura a baseline = valor atual na mesma fonte da tela, deriva a direção, grava o instantâneo e nasce sem nome")
    void deveCriarMetaDeMedida() {
        Money total = source(GoalMetric.TOTAL_DEBT);
        assertThat(total.isPositive()).isTrue();
        GoalDetails d = metric(GoalMetric.TOTAL_DEBT, "0.00", null);
        assertThat(d.goal().kind()).isEqualTo(GoalKind.METRIC);
        assertThat(d.goal().name()).isNull();
        assertThat(d.goal().baseline()).isEqualTo(total);
        assertThat(d.goal().direction()).isEqualTo(GoalDirection.DECREASE);
        assertThat(d.metric().current()).isEqualTo(total);
        assertThat(d.metric().remaining()).isEqualTo(total);
        assertThat(d.progress().ratio()).isEqualByComparingTo("0.0000");
        assertThat(d.metric().asOf()).isEqualTo(TODAY);
        assertThat(d.reservations()).isEmpty();
        assertThat(d.planning().stage()).isEqualTo(GoalStage.FANTASY);
        verify(snapshots).upsert(eq(USER_ID), eq(d.goal().id()), eq(Competence.from(TODAY)), eq(total), eq(NOW));
        verify(snapshots, never()).touchIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("alvo igual ao valor atual é 422; sem medida ou sem alvo é campo obrigatório; mesma medida e alvo ativos é 409 e arquivar libera")
    void deveValidarCriacao() {
        Money total = source(GoalMetric.TOTAL_DEBT);
        assertThatThrownBy(() -> metric(GoalMetric.TOTAL_DEBT, total.toPlainString(), null)).isInstanceOf(GoalMetricTargetEqualsBaselineException.class);
        assertThatThrownBy(() -> create.handle(new CreateGoalCommand(USER_ID, null, Money.of("1.00"), null, null, null, null, GoalKind.METRIC, null)))
                .isInstanceOf(GoalFieldRequiredException.class);
        assertThatThrownBy(() -> create.handle(new CreateGoalCommand(USER_ID, null, null, null, null, null, null, GoalKind.METRIC, GoalMetric.CARD_DEBT)))
                .isInstanceOf(GoalFieldRequiredException.class);
        GoalDetails first = metric(GoalMetric.CARD_DEBT, "100.00", null);
        assertThatThrownBy(() -> metric(GoalMetric.CARD_DEBT, "100.00", null)).isInstanceOf(GoalMetricDuplicateException.class);
        metric(GoalMetric.CARD_DEBT, "200.00", null);
        metric(GoalMetric.PEOPLE_DEBT, "100.00", null);
        archive.handle(new ArchiveGoalCommand(USER_ID, first.goal().id()));
        GoalDetails again = metric(GoalMetric.CARD_DEBT, "100.00", null);
        assertThatThrownBy(() -> unarchive.handle(new UnarchiveGoalCommand(USER_ID, first.goal().id()))).isInstanceOf(GoalMetricDuplicateException.class);
        assertThat(again.goal().id()).isNotEqualTo(first.goal().id());
    }

    @Test
    @DisplayName("sem cartões a medida do cartão vale 0,00; sem dívidas a de pessoas também (falta de dado não é erro)")
    void deveValerZeroSemDados() {
        FinancialRecordsGateway empty = mock(FinancialRecordsGateway.class);
        when(empty.load(any())).thenReturn(new FinancialRecords(List.of(), List.of(), List.of(), List.of()));
        GoalReadModel emptyModel = new GoalReadModel(sources, snapshots, time);
        for (GoalMetric metric : GoalMetric.values()) {
            assertThat(emptyModel.metricValue(USER_ID, metric, empty.load(USER_ID)).isZero()).as(metric.name()).isTrue();
        }
    }

    @Test
    @DisplayName("N metas de medida = uma leitura do razão e uma leitura das recorrências por requisição; só dívidas não lê recorrências")
    void deveLerUmaVezPorRequisicao() {
        metric(GoalMetric.AVAILABLE_BALANCE, "90000.00", null);
        metric(GoalMetric.ACCOUNTS_BALANCE, "90000.00", null);
        metric(GoalMetric.FIXED_COSTS_MONTHLY, "100.00", null);
        metric(GoalMetric.AVAILABLE_BALANCE, "95000.00", null);
        metric(GoalMetric.CARD_DEBT, "10.00", null);
        metric(GoalMetric.TOTAL_DEBT, "10.00", null);
        org.mockito.Mockito.clearInvocations(records, sources, snapshots);
        var page = list.handle(new ListGoalsQuery(USER_ID, null, new com.moneylog.core.sk.page.PageQuery(1, 20)));
        assertThat(page.items()).hasSize(6);
        verify(records, times(1)).load(USER_ID);
        verify(sources, times(1)).recurrences(USER_ID);
        verify(snapshots, times(1)).findByGoals(eq(USER_ID), any());
        for (GoalDetails d : page.items()) {
            assertThat(d.metric().current()).as(d.goal().metric().name()).isEqualTo(source(d.goal().metric()));
        }
        org.mockito.Mockito.clearInvocations(records, sources);
        goals.findAll(USER_ID).stream().filter(v -> v.value().metric() != GoalMetric.CARD_DEBT && v.value().metric() != GoalMetric.TOTAL_DEBT)
                .forEach(v -> archive.handle(new ArchiveGoalCommand(USER_ID, v.value().id())));
        org.mockito.Mockito.clearInvocations(records, sources);
        list.handle(new ListGoalsQuery(USER_ID, null, new com.moneylog.core.sk.page.PageQuery(1, 20)));
        verify(records, times(1)).load(USER_ID);
        verify(sources, never()).recurrences(any());
    }

    @Test
    @DisplayName("meta arquivada não calcula (current/remaining nulos) e não pede a medida; a leitura grava o instantâneo do mês só se faltar")
    void deveNaoCalcularArquivadaETocarInstantaneo() {
        GoalDetails created = metric(GoalMetric.FIXED_COSTS_MONTHLY, "100.00", null);
        archive.handle(new ArchiveGoalCommand(USER_ID, created.goal().id()));
        org.mockito.Mockito.clearInvocations(records, sources, snapshots);
        GoalDetails d = get.handle(new GetGoalQuery(USER_ID, created.goal().id()));
        assertThat(d.status()).isEqualTo(GoalStatus.ARCHIVED);
        assertThat(d.metric().current()).isNull();
        assertThat(d.metric().remaining()).isNull();
        verify(sources, never()).recurrences(any());
        verify(snapshots, never()).touchIfAbsent(any(), any(), any(), any(), any());

        GoalDetails active = metric(GoalMetric.CARD_DEBT, "0.00", null);
        org.mockito.Mockito.clearInvocations(snapshots);
        get.handle(new GetGoalQuery(USER_ID, active.goal().id()));
        Money cardDebt = source(GoalMetric.CARD_DEBT);
        verify(snapshots).touchIfAbsent(eq(USER_ID), eq(active.goal().id()), eq(Competence.from(TODAY)), eq(cardDebt), eq(NOW));
        when(snapshots.findByGoals(any(), any())).thenReturn(Map.of(active.goal().id(),
                List.of(new GoalSnapshot(active.goal().id(), Competence.from(TODAY), cardDebt, NOW))));
        org.mockito.Mockito.clearInvocations(snapshots);
        get.handle(new GetGoalQuery(USER_ID, active.goal().id()));
        verify(snapshots, never()).touchIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("editar: PUT sem alvo e sem nome mantém o alvo; troca o alvo (direção); duplicata é 409; planNote e prazo mudam o estágio")
    void deveEditarMeta() {
        GoalDetails created = metric(GoalMetric.TOTAL_DEBT, "0.00", null);
        UUID id = created.goal().id();
        GoalDetails kept = update.handle(new UpdateGoalCommand(USER_ID, id, "Zerar", null, null, null, null, created.version()));
        assertThat(kept.goal().name()).isEqualTo("Zerar");
        assertThat(kept.goal().target()).isEqualTo(Money.of("0.00"));
        assertThat(kept.planning().stage()).isEqualTo(GoalStage.FANTASY);
        GoalDetails withDeadline = update.handle(new UpdateGoalCommand(USER_ID, id, "Zerar", null, LocalDate.of(2027, 10, 31), null, null, kept.version(), "Pagar 100 por mês"));
        assertThat(withDeadline.planning().stage()).isEqualTo(GoalStage.STRATEGY);
        assertThat(withDeadline.planning().plan().monthsLeft()).isEqualTo(13);
        GoalDetails noDeadline = update.handle(new UpdateGoalCommand(USER_ID, id, "Zerar", null, null, null, null, withDeadline.version(), "Pagar 100 por mês"));
        assertThat(noDeadline.planning().stage()).isEqualTo(GoalStage.FANTASY);
        assertThat(noDeadline.planning().plan()).isNull();
        metric(GoalMetric.TOTAL_DEBT, "50.00", null);
        assertThatThrownBy(() -> update.handle(new UpdateGoalCommand(USER_ID, id, null, Money.of("50.00"), null, null, null, noDeadline.version())))
                .isInstanceOf(GoalMetricDuplicateException.class);
    }

    @Test
    @DisplayName("ações: com plano automático + ação declarada a meta vira STRATEGY; sem declarar fica OBJECTIVE com a sugestão visível")
    void deveDerivarEstagioComAcoes() {
        GoalDetails created = create.handle(new CreateGoalCommand(USER_ID, null, Money.of("0.00"), LocalDate.of(2027, 10, 31), null, null, null,
                GoalKind.METRIC, GoalMetric.TOTAL_DEBT));
        assertThat(created.planning().stage()).isEqualTo(GoalStage.OBJECTIVE);
        assertThat(created.planning().plan().requiredPace().isPositive()).isTrue();
        GoalDetails withAction = actions.handle(new AddGoalActionCommand(USER_ID, created.goal().id(), "Pagar a fatura em dia", GoalActionFrequency.MONTHLY));
        assertThat(withAction.planning().stage()).isEqualTo(GoalStage.STRATEGY);
        UUID actionId = withAction.planning().actions().getFirst().id();
        GoalDetails checked = actions.handle(new CheckInGoalActionCommand(USER_ID, created.goal().id(), actionId, "2026-10", true));
        assertThat(checked.planning().actions().getFirst().doneIn()).containsExactly("2026-10");
    }

    @Test
    @DisplayName("re-ancorar METRIC usa o valor atual da mesma fonte e grava o instantâneo; quitar dívidas segue igual; poupar recusa")
    void deveReancorar() {
        GoalDetails created = metric(GoalMetric.TOTAL_DEBT, "0.00", null);
        Money before = created.goal().baseline();
        scenario.compraNoCartao("300.00", 1);
        Money after = source(GoalMetric.TOTAL_DEBT);
        assertThat(after).isEqualTo(before.add(Money.of("300.00")));
        org.mockito.Mockito.clearInvocations(snapshots);
        GoalDetails anchored = anchor.handle(new AnchorDebtPayoffBaselineCommand(USER_ID, created.goal().id()));
        assertThat(anchored.goal().baseline()).isEqualTo(after);
        assertThat(anchored.progress().ratio()).isEqualByComparingTo("0.0000");
        verify(snapshots).upsert(eq(USER_ID), eq(created.goal().id()), eq(Competence.from(TODAY)), eq(after), eq(NOW));
        GoalDetails equal = metric(GoalMetric.CARD_DEBT, source(GoalMetric.CARD_DEBT).add(Money.of("100.00")).toPlainString(), null);
        scenario.compraNoCartao("100.00", 1);
        assertThatThrownBy(() -> anchor.handle(new AnchorDebtPayoffBaselineCommand(USER_ID, equal.goal().id())))
                .isInstanceOf(GoalMetricTargetEqualsBaselineException.class);
        assertThat(goals.findById(USER_ID, equal.goal().id()).orElseThrow().value().direction()).isEqualTo(GoalDirection.INCREASE);
    }

    @Test
    @DisplayName("meta de medida não recebe reservas")
    void naoDeveReservar() {
        GoalDetails created = metric(GoalMetric.TOTAL_DEBT, "0.00", null);
        assertThatThrownBy(() -> goals.findById(USER_ID, created.goal().id()).orElseThrow().value()
                .reserve(UUID.randomUUID(), null, TODAY, null, NOW)).isInstanceOf(GoalKindNotReservableException.class);
    }

    @Test
    @DisplayName("job diário: um instantâneo por meta rastreada, uma leitura do razão; sem metas, nada")
    void deveGravarInstantaneosDoJob() {
        var job = new RecordGoalSnapshotsUseCaseImpl(goals, records, sources, time, model);
        assertThat(job.handle(USER_ID)).isZero();
        metric(GoalMetric.TOTAL_DEBT, "0.00", null);
        metric(GoalMetric.ACCOUNTS_BALANCE, "999999.00", null);
        org.mockito.Mockito.clearInvocations(records, snapshots);
        assertThat(job.handle(USER_ID)).isEqualTo(2);
        verify(records, times(1)).load(USER_ID);
        verify(snapshots, times(2)).upsert(eq(USER_ID), any(), eq(Competence.from(TODAY)), any(), eq(NOW));
    }

    @Test
    @DisplayName("estágio vale para todo tipo: SAVINGS sem prazo é FANTASY, com prazo OBJECTIVE (plano parte de 0,00); quitar dívidas só tem plano depois de ancorar")
    void deveDerivarEstagioNosOutrosTipos() {
        GoalDetails noDeadline = create.handle(new CreateGoalCommand(USER_ID, "Viagem", Money.of("1000.00"), null, null, null, null));
        assertThat(noDeadline.planning().stage()).isEqualTo(GoalStage.FANTASY);
        assertThat(noDeadline.planning().plan()).isNull();
        GoalDetails withDeadline = create.handle(new CreateGoalCommand(USER_ID, "Carro", Money.of("1200.00"), LocalDate.of(2027, 9, 30), null, null, null));
        assertThat(withDeadline.planning().stage()).isEqualTo(GoalStage.OBJECTIVE);
        assertThat(withDeadline.planning().plan().monthsLeft()).isEqualTo(12);
        assertThat(withDeadline.planning().plan().requiredPace()).isEqualTo(Money.of("100.00"));
        assertThat(withDeadline.planning().plan().nextMilestone()).isEqualTo(Money.of("100.00"));
        assertThat(withDeadline.planning().consistency().history()).hasSize(1);

        GoalDetails payoff = create.handle(new CreateGoalCommand(USER_ID, null, null, LocalDate.of(2027, 9, 30), null, null, null, GoalKind.DEBT_PAYOFF));
        assertThat(payoff.planning().stage()).isEqualTo(GoalStage.OBJECTIVE);
        assertThat(payoff.planning().plan()).as("montando: sem trajetória").isNull();
        GoalDetails anchored = anchor.handle(new AnchorDebtPayoffBaselineCommand(USER_ID, payoff.goal().id()));
        assertThat(anchored.planning().plan().monthsLeft()).isEqualTo(12);
        assertThat(anchored.planning().plan().requiredPace()).isEqualTo(Money.of("116.67"));
    }
}
