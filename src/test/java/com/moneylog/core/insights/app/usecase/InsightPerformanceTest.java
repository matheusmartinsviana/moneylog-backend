package com.moneylog.core.insights.app.usecase;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.goal.app.gateways.GoalSnapshotGateway;
import com.moneylog.core.insights.app.InsightContextFactory;
import com.moneylog.core.insights.app.gateways.InsightDismissalGateway;
import com.moneylog.core.insights.app.usecase.InsightUseCases.DailyQuery;
import com.moneylog.core.insights.app.usecase.InsightUseCases.HistoryQuery;
import com.moneylog.core.insights.app.usecase.InsightUseCases.MonthlyQuery;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionHeader;

/**
 * Desempenho (T301, ADR-008): 5 anos de dados sintéticos (mais de 20 mil lançamentos). Cada requisição faz <b>uma</b> leitura do razão, inclusive o
 * histórico de 12 meses; o cálculo em memória fica abaixo de 500 ms (p95) numa máquina de desenvolvimento. O limite do teste é folgado
 * (3 s) para não ficar frágil em CI com carga; o número medido é impresso.
 */
@DisplayName("Insights: 5 anos de dados, 1 leitura do razão por requisição (T301)")
class InsightPerformanceTest {

    private static final int EXPENSES = 20_000;
    private static final long CI_LIMIT_MS = 3_000;

    @Test
    @DisplayName("daily, monthly e history de 12 meses com 20 mil lançamentos: 1 leitura do razão cada e tempo dentro do limite")
    void deveLerUmaVezComMuitosDados() {
        FinanceScenarioFactory s = umCenarioBase();
        Random random = new Random(5L);
        LocalDate today = LocalDate.of(2026, 10, 10);
        for (int i = 0; i < EXPENSES; i++) {
            LocalDate date = today.minusDays(random.nextInt(5 * 365));
            s.transactions.add(AccountTransactions.expense(TransactionHeader.of(UUID.randomUUID(), USER_ID, date, "Compra " + (i % 40)), Money.ofCents(500 + random.nextInt(30_000),
                    com.moneylog.core.sk.money.CurrencyCode.BRL), s.nubank, s.alimentacao, PaymentMethod.PIX));
            if (i % 40 == 0) {
                s.transactions.add(AccountTransactions.income(TransactionHeader.of(UUID.randomUUID(), USER_ID, date, "Renda"), Money.ofCents(300_000, com.moneylog.core.sk.money.CurrencyCode.BRL),
                        s.nubank, s.salario, PaymentMethod.PIX));
            }
        }
        FinancialRecordsGateway records = mock(FinancialRecordsGateway.class);
        CommitmentSourcesGateway sources = mock(CommitmentSourcesGateway.class);
        CategoryGateway categories = mock(CategoryGateway.class);
        InsightDismissalGateway dismissals = mock(InsightDismissalGateway.class);
        var loaded = s.records();
        when(records.load(any())).thenReturn(loaded);
        when(sources.recurrences(any())).thenReturn(List.of());
        when(categories.list(any(), any(), anyBoolean())).thenReturn(List.of());
        when(dismissals.findActive(any())).thenReturn(List.of());
        TimeProvider time = new ClockTimeProvider(Clock.fixed(Instant.parse("2026-10-10T15:00:00Z"), ZoneOffset.UTC));
        InsightContextFactory contexts = new InsightContextFactory(records, sources, categories, mock(GoalSnapshotGateway.class), time);
        var monthly = new InsightUseCasesImpl.GetMonthlyInsights(contexts, dismissals, time);
        var daily = new InsightUseCasesImpl.GetDailyInsights(contexts, dismissals, time);
        var history = new InsightUseCasesImpl.GetInsightHistory(contexts, dismissals, time);

        List<Long> dailyMs = new ArrayList<>();
        List<Long> monthlyMs = new ArrayList<>();
        for (int run = 0; run < 5; run++) {
            long t0 = System.nanoTime();
            daily.handle(new DailyQuery(USER_ID, null, false, 0, 3));
            long t1 = System.nanoTime();
            monthly.handle(new MonthlyQuery(USER_ID, null, false, 0, 20));
            long t2 = System.nanoTime();
            dailyMs.add((t1 - t0) / 1_000_000);
            monthlyMs.add((t2 - t1) / 1_000_000);
        }
        verify(records, times(10)).load(USER_ID);
        verify(sources, times(10)).recurrences(USER_ID);

        long h0 = System.nanoTime();
        var months = history.handle(new HistoryQuery(USER_ID, Competence.of(2025, 11), Competence.of(2026, 10), 10));
        long historyMs = (System.nanoTime() - h0) / 1_000_000;
        verify(records, times(11)).load(USER_ID);
        verify(sources, times(11)).recurrences(USER_ID);

        System.out.println("[insights perf] lançamentos=" + loaded.transactions().size() + " daily(ms)=" + dailyMs + " monthly(ms)=" + monthlyMs
                + " history12(ms)=" + historyMs);
        assertThat(months).hasSize(12);
        assertThat(dailyMs).allSatisfy(ms -> assertThat(ms).isLessThan(CI_LIMIT_MS));
        assertThat(monthlyMs).allSatisfy(ms -> assertThat(ms).isLessThan(CI_LIMIT_MS));
        assertThat(historyMs).isLessThan(CI_LIMIT_MS * 2);
    }
}
