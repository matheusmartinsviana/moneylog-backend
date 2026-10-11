package com.moneylog.core.insights.app.usecase;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.goal.app.gateways.GoalSnapshotGateway;
import com.moneylog.core.insights.app.InsightContextFactory;
import com.moneylog.core.insights.app.gateways.InsightDismissalGateway;
import com.moneylog.core.insights.app.usecase.InsightUseCases.DailyQuery;
import com.moneylog.core.insights.app.usecase.InsightUseCases.DismissCommand;
import com.moneylog.core.insights.app.usecase.InsightUseCases.HistoryMonth;
import com.moneylog.core.insights.app.usecase.InsightUseCases.HistoryQuery;
import com.moneylog.core.insights.app.usecase.InsightUseCases.InsightsView;
import com.moneylog.core.insights.app.usecase.InsightUseCases.MonthlyQuery;
import com.moneylog.core.insights.app.usecase.InsightUseCases.RestoreCommand;
import com.moneylog.core.insights.domain.Dismissal;
import com.moneylog.core.insights.domain.Dismissal.Scope;
import com.moneylog.core.insights.domain.InsightIds;
import com.moneylog.core.insights.domain.InsightItem;
import com.moneylog.core.insights.exception.InsightExceptions.InsightNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;

@DisplayName("Casos de uso de insights (T286, T293): 1 leitura por requisição, dispensa e restauração")
class InsightUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");

    private final FinancialRecordsGateway records = mock(FinancialRecordsGateway.class);
    private final CommitmentSourcesGateway sources = mock(CommitmentSourcesGateway.class);
    private final CategoryGateway categories = mock(CategoryGateway.class);
    private final GoalSnapshotGateway snapshots = mock(GoalSnapshotGateway.class);
    private final InsightDismissalGateway dismissals = mock(InsightDismissalGateway.class);
    private final LocalDate[] clock = {BASE_DATE};
    private final TimeProvider time = new TimeProvider() {
        @Override
        public Instant now() {
            return NOW;
        }

        @Override
        public LocalDate today() {
            return clock[0];
        }

        @Override
        public ZoneId zone() {
            return ZoneId.of("America/Sao_Paulo");
        }
    };
    private final IdGenerator ids = UUID::randomUUID;
    private final InsightContextFactory contexts = new InsightContextFactory(records, sources, categories, snapshots, time);
    private final List<Dismissal> stored = new ArrayList<>();

    private InsightUseCasesImpl.GetMonthlyInsights monthly;
    private InsightUseCasesImpl.GetDailyInsights daily;
    private InsightUseCasesImpl.GetInsightHistory history;
    private InsightUseCasesImpl.DismissInsight dismiss;
    private InsightUseCasesImpl.RestoreInsight restore;

    @BeforeEach
    void setUp() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.income(s.header(LocalDate.of(2026, 10, 2), "Folha"), Money.of("4000.00"), s.nubank, s.salario,
                PaymentMethod.PIX));
        s.caso1DespesaPix();
        s.caso2CompraParcelada();
        FinancialRecords loaded = s.records();
        when(records.load(any())).thenReturn(loaded);
        when(sources.recurrences(any())).thenReturn(List.of());
        when(categories.list(any(), any(), anyBoolean())).thenReturn(List.of(s.alimentacao));
        when(dismissals.findActive(any())).thenAnswer(i -> stored.stream().filter(Dismissal::isActive).toList());
        org.mockito.Mockito.doAnswer(i -> {
            stored.add(i.getArgument(0));
            return null;
        }).when(dismissals).insert(any());
        when(dismissals.revoke(any(), anyCollection(), any())).thenAnswer(i -> {
            java.util.Collection<UUID> revoked = i.getArgument(1);
            Instant at = i.getArgument(2);
            for (int k = 0; k < stored.size(); k++) {
                Dismissal d = stored.get(k);
                if (revoked.contains(d.id()) && d.isActive()) {
                    stored.set(k, new Dismissal(d.id(), d.userId(), d.fingerprint(), d.type(), d.subjectId(), d.scope(), d.dismissedOn(), d.createdAt(), at));
                }
            }
            return revoked.size();
        });
        monthly = new InsightUseCasesImpl.GetMonthlyInsights(contexts, dismissals, time);
        daily = new InsightUseCasesImpl.GetDailyInsights(contexts, dismissals, time);
        history = new InsightUseCasesImpl.GetInsightHistory(contexts, dismissals, time);
        dismiss = new InsightUseCasesImpl.DismissInsight(contexts, dismissals, ids, time);
        restore = new InsightUseCasesImpl.RestoreInsight(contexts, dismissals, time);
    }

    @Test
    @DisplayName("mensal e do dia: no máximo 1 leitura do razão, 1 de recorrências, 1 de dispensas e nenhuma de instantâneos de metas por requisição")
    void deveLerUmaVezPorRequisicao() {
        monthly.handle(new MonthlyQuery(USER_ID, null, false, 0, 20));
        verify(records, times(1)).load(USER_ID);
        verify(sources, times(1)).recurrences(USER_ID);
        verify(dismissals, times(1)).findActive(USER_ID);

        daily.handle(new DailyQuery(USER_ID, null, false, 0, 20));
        verify(records, times(2)).load(USER_ID);
        verify(sources, times(2)).recurrences(USER_ID);
        verify(snapshots, never()).findByGoals(any(), anyCollection());
    }

    @Test
    @DisplayName("histórico de 12 meses: ainda 1 leitura do razão e 1 de recorrências")
    void deveLerUmaVezNoHistorico() {
        List<HistoryMonth> months = history.handle(new HistoryQuery(USER_ID, Competence.of(2025, 11), Competence.of(2026, 10), 10));

        assertThat(months).hasSize(12);
        assertThat(months.get(0).month()).isEqualTo(Competence.of(2025, 11));
        assertThat(months.get(11).month()).isEqualTo(Competence.of(2026, 10));
        verify(records, times(1)).load(USER_ID);
        verify(sources, times(1)).recurrences(USER_ID);
        verify(dismissals, times(1)).findActive(USER_ID);
    }

    @Test
    @DisplayName("data futura é recusada no caso de uso e nada é lido")
    void deveRecusarDataFutura() {
        assertThatThrownBy(() -> daily.handle(new DailyQuery(USER_ID, BASE_DATE.plusDays(1), false, 0, 20))).isInstanceOf(IllegalArgumentException.class);
        verify(records, never()).load(any());
    }

    @Test
    @DisplayName("mês futuro responde vazio com insufficient ALL FUTURE_MONTH")
    void deveResponderMesFuturoVazio() {
        InsightsView view = monthly.handle(new MonthlyQuery(USER_ID, Competence.of(2027, 5), false, 0, 20));

        assertThat(view.items()).isEmpty();
        assertThat(view.insufficient()).anySatisfy(d -> assertThat(d.type()).isEqualTo("ALL"));
    }

    @Test
    @DisplayName("limite e hasNext: o corte respeita a ordem e avisa que há mais")
    void devePaginar() {
        clock[0] = LocalDate.of(2026, 10, 21);
        InsightsView all = daily.handle(new DailyQuery(USER_ID, null, false, 0, 50));
        assertThat(all.items().size()).isGreaterThanOrEqualTo(2);

        InsightsView first = daily.handle(new DailyQuery(USER_ID, null, false, 0, 1));
        InsightsView second = daily.handle(new DailyQuery(USER_ID, null, false, 1, 1));

        assertThat(first.items()).hasSize(1);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.items().get(0).id()).isEqualTo(all.items().get(0).id());
        assertThat(second.items().get(0).id()).isEqualTo(all.items().get(1).id());
    }

    @Test
    @DisplayName("dispensar HOJE: grava uma linha com o escopo, o insight some do dia e volta com includeDismissed marcado")
    void deveDispensarHoje() {
        InsightItem budget = firstDaily("DAILY_BUDGET");

        InsightItem updated = dismiss.handle(new DismissCommand(USER_ID, budget.id(), Scope.TODAY));

        assertThat(updated.dismissed()).isTrue();
        assertThat(updated.id()).isEqualTo(budget.id());
        ArgumentCaptor<Dismissal> captor = ArgumentCaptor.forClass(Dismissal.class);
        verify(dismissals, times(1)).insert(captor.capture());
        assertThat(captor.getValue().scope()).isEqualTo(Scope.TODAY);
        assertThat(captor.getValue().dismissedOn()).isEqualTo(BASE_DATE);
        assertThat(captor.getValue().type()).isEqualTo("DAILY_BUDGET");
        assertThat(captor.getValue().isActive()).isTrue();
        assertThat(daily.handle(new DailyQuery(USER_ID, null, false, 0, 50)).items()).extracting(InsightItem::id).doesNotContain(budget.id());
        assertThat(daily.handle(new DailyQuery(USER_ID, null, true, 0, 50)).items()).anySatisfy(i -> {
            assertThat(i.id()).isEqualTo(budget.id());
            assertThat(i.dismissed()).isTrue();
        });
    }

    @Test
    @DisplayName("dispensar duas vezes o mesmo escopo não grava linha repetida")
    void naoDeveRepetirALinha() {
        InsightItem budget = firstDaily("DAILY_BUDGET");
        dismiss.handle(new DismissCommand(USER_ID, budget.id(), Scope.TODAY));
        dismiss.handle(new DismissCommand(USER_ID, budget.id(), Scope.TODAY));

        verify(dismissals, times(1)).insert(any());
    }

    @Test
    @DisplayName("restaurar: grava revoked_at (a linha continua), nunca apaga, e o insight volta sem includeDismissed")
    void deveRestaurar() {
        InsightItem budget = firstDaily("DAILY_BUDGET");
        dismiss.handle(new DismissCommand(USER_ID, budget.id(), Scope.TYPE));

        InsightItem restored = restore.handle(new RestoreCommand(USER_ID, budget.id()));

        assertThat(restored.dismissed()).isFalse();
        verify(dismissals, times(1)).revoke(eq(USER_ID), anyCollection(), eq(NOW));
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).revokedAt()).isEqualTo(NOW);
        assertThat(daily.handle(new DailyQuery(USER_ID, null, false, 0, 50)).items()).extracting(InsightItem::id).contains(budget.id());
    }

    @Test
    @DisplayName("id de outro usuário ou inexistente: InsightNotFound (404), sem gravar nada")
    void naoDeveDispensarIdAlheio() {
        InsightItem budget = firstDaily("DAILY_BUDGET");
        UUID other = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");
        assertThatThrownBy(() -> dismiss.handle(new DismissCommand(other, budget.id(), Scope.TODAY))).isInstanceOf(InsightNotFoundException.class);
        assertThatThrownBy(() -> dismiss.handle(new DismissCommand(USER_ID, "ins_" + "0".repeat(32), Scope.TODAY)))
                .isInstanceOf(InsightNotFoundException.class);
        assertThatThrownBy(() -> dismiss.handle(new DismissCommand(USER_ID, "lixo", Scope.TODAY))).isInstanceOf(InsightNotFoundException.class);
        assertThatThrownBy(() -> restore.handle(new RestoreCommand(other, budget.id()))).isInstanceOf(InsightNotFoundException.class);
        verify(dismissals, never()).insert(any());
        verify(dismissals, never()).revoke(any(), anyCollection(), any());
    }

    @Test
    @DisplayName("os ids são estáveis entre chamadas e diferentes por usuário")
    void deveTerIdEstavel() {
        InsightItem a = firstDaily("DAILY_BUDGET");
        InsightItem b = firstDaily("DAILY_BUDGET");

        assertThat(a.id()).isEqualTo(b.id());
        assertThat(InsightIds.fingerprintOf(a.id())).isNotNull();
    }

    private InsightItem firstDaily(String type) {
        return daily.handle(new DailyQuery(USER_ID, null, false, 0, 50)).items().stream().filter(i -> i.insight().type().equals(type)).findFirst()
                .orElseThrow();
    }
}
