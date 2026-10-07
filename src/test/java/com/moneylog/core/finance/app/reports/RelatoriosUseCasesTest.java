package com.moneylog.core.finance.app.reports;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.reports.usecase.GetIncomeExpenseSeriesUseCase.GetIncomeExpenseSeriesQuery;
import com.moneylog.core.finance.app.reports.usecase.GetIncomeExpenseSeriesUseCaseImpl;
import com.moneylog.core.finance.app.reports.usecase.GetSpendingByCategoryUseCase.GetSpendingByCategoryQuery;
import com.moneylog.core.finance.app.reports.usecase.GetSpendingByCategoryUseCase.SpendingByCategoryView;
import com.moneylog.core.finance.app.reports.usecase.GetSpendingByCategoryUseCaseImpl;
import com.moneylog.core.finance.domain.IncomeExpenseSeries;
import com.moneylog.core.finance.domain.ReportPeriod;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Casos de uso dos relatórios do Início (T184, T185): uma só carga do razão, o relógio injetado e nenhuma escrita")
class RelatoriosUseCasesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    @Mock
    private FinancialRecordsGateway recordsGateway;
    @Mock
    private CategoryGateway categories;
    @Mock
    private TimeProvider time;

    private final FinanceScenarioFactory scenario = umCenarioBase();

    @BeforeEach
    void setUp() {
        when(time.today()).thenReturn(TODAY);
        scenario.caso1DespesaPix();
        scenario.caso2CompraParcelada();
        when(recordsGateway.load(USER_ID)).thenReturn(scenario.records());
        when(categories.list(USER_ID, null, true)).thenReturn(List.of(scenario.alimentacao, scenario.salario));
    }

    @Test
    @DisplayName("série: carrega o razão uma vez, usa o hoje do relógio e devolve o cálculo do domínio")
    void deveCalcularASerie() {
        IncomeExpenseSeries series = new GetIncomeExpenseSeriesUseCaseImpl(recordsGateway, time)
                .handle(new GetIncomeExpenseSeriesQuery(USER_ID, ReportPeriod.D7));

        verify(recordsGateway, times(1)).load(USER_ID);
        assertThat(series.to()).isEqualTo(TODAY);
        assertThat(series.points()).hasSize(7);
        assertThat(series.totals().expenses()).as("Pix de 100 + a parcela 1/6 de 200").isEqualTo(Money.of("300.00"));
    }

    @Test
    @DisplayName("consumo por categoria: month nulo = a competência de hoje; resolve as categorias (inclusive arquivadas) e carrega o razão uma vez")
    void deveCalcularOConsumoPorCategoria() {
        SpendingByCategoryView view = new GetSpendingByCategoryUseCaseImpl(recordsGateway, categories, time)
                .handle(new GetSpendingByCategoryQuery(USER_ID, null, 5));

        verify(recordsGateway, times(1)).load(USER_ID);
        verify(categories).list(USER_ID, null, true);
        assertThat(view.spending().competence()).isEqualTo(Competence.parse("2026-10"));
        assertThat(view.spending().items()).hasSize(1);
        assertThat(view.spending().items().get(0).amount()).isEqualTo(Money.of("300.00"));
        assertThat(view.categories().get(scenario.alimentacao.id())).extracting(Category::name).isEqualTo(scenario.alimentacao.name());
    }

    @Test
    @DisplayName("consumo por categoria: month explícito de outro mês")
    void deveUsarOMesInformado() {
        SpendingByCategoryView view = new GetSpendingByCategoryUseCaseImpl(recordsGateway, categories, time)
                .handle(new GetSpendingByCategoryQuery(USER_ID, Competence.parse("2026-11"), 3));

        assertThat(view.spending().items()).hasSize(1);
        assertThat(view.spending().items().get(0).amount()).as("parcela 2/6").isEqualTo(Money.of("200.00"));
        verify(time, never()).now();
    }
}
