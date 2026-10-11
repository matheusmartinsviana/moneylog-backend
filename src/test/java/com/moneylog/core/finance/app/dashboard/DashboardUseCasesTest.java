package com.moneylog.core.finance.app.dashboard;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.finance.app.dashboard.usecase.GetDashboardSummaryUseCase.GetDashboardSummaryQuery;
import com.moneylog.core.finance.app.dashboard.usecase.GetDashboardSummaryUseCaseImpl;
import com.moneylog.core.finance.app.dashboard.usecase.GetUpcomingUseCase.GetUpcomingQuery;
import com.moneylog.core.finance.app.dashboard.usecase.GetUpcomingUseCaseImpl;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.Bucket;
import com.moneylog.core.finance.domain.CashFlowReport;
import com.moneylog.core.finance.domain.CommitmentKind;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.finance.util.ListScenario;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Casos de uso do dashboard (T29, S-61, S-64, S-79)")
class DashboardUseCasesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

    @Mock
    private FinancialRecordsGateway recordsGateway;
    @Mock
    private CommitmentSourcesGateway sources;
    @Mock
    private TimeProvider time;

    private GetDashboardSummaryUseCaseImpl summary;
    private GetUpcomingUseCaseImpl upcoming;

    @BeforeEach
    void setUp() {
        when(time.today()).thenReturn(TODAY);
        when(sources.recurrences(USER_ID)).thenReturn(List.of());
        summary = new GetDashboardSummaryUseCaseImpl(recordsGateway, sources, time);
        upcoming = new GetUpcomingUseCaseImpl(recordsGateway, sources, time);
    }

    private void records(FinancialRecords records) {
        when(recordsGateway.load(USER_ID)).thenReturn(records);
    }

    @Test
    @DisplayName("sem contas: hasAccounts é false e todos os valores são 0,00 (S-61)")
    void deveInformarQueNaoHaContas() {
        records(new FinancialRecords(List.of(), List.of(), List.of(), List.of()));

        DashboardView view = summary.handle(new GetDashboardSummaryQuery(USER_ID, null));

        assertThat(view.hasAccounts()).isFalse();
        assertThat(view.summary().balances().inAccounts()).isEqualTo(Money.of("0.00"));
        assertThat(view.summary().balances().available()).isEqualTo(Money.of("0.00"));
        assertThat(view.summary().month().consumption()).isEqualTo(Money.of("0.00"));
        assertThat(view.summary().month().income()).isEqualTo(Money.of("0.00"));
        assertThat(view.summary().cashFlow().net()).isEqualTo(Money.of("0.00"));
        assertThat(view.summary().netWorth().total()).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("só uma conta arquivada: hasAccounts continua true (S-61)")
    void deveContarContaArquivada() {
        records(new FinancialRecords(List.of(AccountTestFactory.umaContaArquivada()), List.of(), List.of(), List.of()));

        assertThat(summary.handle(new GetDashboardSummaryQuery(USER_ID, null)).hasAccounts()).isTrue();
    }

    @Test
    @DisplayName("o resumo é DashboardSummary.of sobre o carregamento único: o mês e o fluxo de caixa são os das calculadoras (S-79); asOf padrão = hoje")
    void deveMontarOResumoPeloCaminhoUnico() {
        ListScenario s = new ListScenario();
        FinancialRecords loaded = s.records();
        records(loaded);

        DashboardView view = summary.handle(new GetDashboardSummaryQuery(USER_ID, LocalDate.of(2026, 10, 31)));

        DashboardSummary direct = DashboardSummary.of(loaded, List.of(), LocalDate.of(2026, 10, 31), TODAY);
        assertThat(view.summary()).isEqualTo(direct);
        assertThat(view.summary().month().consumption()).isEqualTo(Money.of("90.00"));
        assertThat(view.summary().month().income()).isEqualTo(Money.of("3000.00"));
        assertThat(view.summary().month().net()).isEqualTo(Money.of("2910.00"));
        assertThat(view.summary().balances().inAccounts()).isEqualTo(Money.of("22410.00"));
        assertThat(view.summary().balances().investments()).isEqualTo(Money.of("500.00"));
        assertThat(view.summary().balances().committed30d()).isEqualTo(Money.of("70.00"));
        assertThat(view.summary().balances().available()).isEqualTo(Money.of("22340.00"));
        assertThat(view.summary().cashFlow()).isEqualTo(CashFlowReport.between(loaded, loaded.ledger(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));
        assertThat(view.summary().isProjection()).isFalse();
        assertThat(summary.handle(new GetDashboardSummaryQuery(USER_ID, null)).summary().asOf()).isEqualTo(TODAY);
        assertThat(view.summary().month().competence()).isEqualTo(Competence.of(2026, 10));
        verify(sources, org.mockito.Mockito.atLeastOnce()).recurrences(USER_ID);
    }

    @Test
    @DisplayName("asOf depois de hoje marca a projeção")
    void deveMarcarProjecao() {
        records(new ListScenario().records());

        assertThat(summary.handle(new GetDashboardSummaryQuery(USER_ID, TODAY.plusDays(1))).summary().isProjection()).isTrue();
        assertThat(summary.handle(new GetDashboardSummaryQuery(USER_ID, TODAY)).summary().isProjection()).isFalse();
    }

    @Test
    @DisplayName("upcoming sem parâmetros: de hoje a hoje + 30 dias; sem fontes, só a movimentação futura de 05/11 (S-64, S-258)")
    void deveDevolverVazioSemFontes() {
        records(new ListScenario().records());

        UpcomingView view = upcoming.handle(new GetUpcomingQuery(USER_ID, null, null));

        assertThat(view.items()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(CommitmentKind.SCHEDULED_TRANSACTION);
            assertThat(c.dueDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        });
        assertThat(view.undated()).isEmpty();
        assertThat(view.out()).isEqualTo(Money.of("70.00"));
        assertThat(view.in()).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("upcoming com fontes: janela [from, to], vencidos entram sempre, dívida sem data vai para undated, totais só dos itens datados")
    void deveFiltrarAJanelaEIncluirVencidos() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso6Emprestimo();
        s.caso8DividaSemMovimentacao();
        records(s.records());

        UpcomingView defaultWindow = upcoming.handle(new GetUpcomingQuery(USER_ID, null, null));
        assertThat(defaultWindow.items()).extracting(c -> c.kind()).containsExactlyInAnyOrder(CommitmentKind.DEBT_RECEIVABLE, CommitmentKind.DEBT_PAYABLE);
        assertThat(defaultWindow.out()).isEqualTo(Money.of("200.00"));
        assertThat(defaultWindow.in()).isEqualTo(Money.of("300.00"));
        assertThat(defaultWindow.items()).extracting(c -> c.bucket()).containsOnly(Bucket.NEXT_7_DAYS);

        assertThat(upcoming.handle(new GetUpcomingQuery(USER_ID, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31))).items()).isEmpty();
        assertThat(upcoming.handle(new GetUpcomingQuery(USER_ID, LocalDate.of(2026, 11, 1), null)).items()).hasSize(2);
        assertThat(upcoming.handle(new GetUpcomingQuery(USER_ID, null, LocalDate.of(2026, 11, 1))).items()).isEmpty();

        when(time.today()).thenReturn(LocalDate.of(2026, 11, 20));
        UpcomingView later = upcoming.handle(new GetUpcomingQuery(USER_ID, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)));
        assertThat(later.items()).hasSize(2).extracting(c -> c.bucket()).containsOnly(Bucket.OVERDUE);
    }

    @Test
    @DisplayName("from depois de to é recusado pelo caso de uso (a fronteira web responde 400 antes) e nada é lido")
    void deveRecusarIntervaloInvertido() {
        assertThatThrownBy(() -> upcoming.handle(new GetUpcomingQuery(USER_ID, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(recordsGateway, never()).load(USER_ID);
    }

    @Test
    @DisplayName("S-240: as ocorrências de recorrência vencidas e pendentes vêm limitadas às 500 mais recentes (overdueTruncated); totais completos; dívida e fatura vencidas não são limitadas; com 500 exatas não trunca")
    void deveLimitarAsOcorrenciasVencidas() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso6Emprestimo();
        records(s.records());
        var salary = com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeDespesa();
        var recurrence = com.moneylog.core.recurrence.domain.RecurringTransaction.ofAccount(java.util.UUID.randomUUID(), USER_ID,
                com.moneylog.core.transaction.domain.TransactionType.EXPENSE, Money.of("1.00"), "Diária", salary.id(),
                s.nubank.id(), com.moneylog.core.transaction.domain.PaymentMethod.PIX,
                com.moneylog.core.recurrence.domain.Schedule.of(com.moneylog.core.recurrence.domain.Frequency.DAILY, TODAY.minusDays(600)));
        when(sources.recurrences(USER_ID)).thenReturn(List.of(recurrence));

        UpcomingView view = upcoming.handle(new GetUpcomingQuery(USER_ID, null, null));

        long overdueOccurrences = view.items().stream().filter(c -> c.kind() == CommitmentKind.RECURRENCE && c.bucket() == Bucket.OVERDUE).count();
        assertThat(view.overdueTruncated()).isTrue();
        assertThat(overdueOccurrences).isEqualTo(500);
        assertThat(view.items().stream().filter(c -> c.kind() == CommitmentKind.RECURRENCE && c.bucket() == Bucket.OVERDUE).map(c -> c.dueDate()).min(java.util.Comparator.naturalOrder()))
                .contains(TODAY.minusDays(500));
        assertThat(view.items()).extracting(c -> c.kind()).contains(CommitmentKind.DEBT_RECEIVABLE);
        assertThat(view.out()).isEqualTo(Money.of("631.00"));
        assertThat(view.items().stream().filter(c -> c.bucket() != Bucket.OVERDUE).count()).isGreaterThan(30);

        var exact = com.moneylog.core.recurrence.domain.RecurringTransaction.ofAccount(java.util.UUID.randomUUID(), USER_ID,
                com.moneylog.core.transaction.domain.TransactionType.EXPENSE, Money.of("1.00"), "Diária", salary.id(),
                s.nubank.id(), com.moneylog.core.transaction.domain.PaymentMethod.PIX,
                com.moneylog.core.recurrence.domain.Schedule.of(com.moneylog.core.recurrence.domain.Frequency.DAILY, TODAY.minusDays(500)));
        when(sources.recurrences(USER_ID)).thenReturn(List.of(exact));
        UpcomingView untouched = upcoming.handle(new GetUpcomingQuery(USER_ID, null, null));
        assertThat(untouched.overdueTruncated()).isFalse();
        assertThat(untouched.items().stream().filter(c -> c.kind() == CommitmentKind.RECURRENCE && c.bucket() == Bucket.OVERDUE)).hasSize(500);
    }
}
