package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.DebtOverview;
import com.moneylog.core.finance.domain.DebtOverviewCalculator;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.GoalMetricValues;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.goal.domain.GoalMetric;
import com.moneylog.core.recurrence.domain.MonthlyEstimate;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

/**
 * ADR-016: para cenários aleatórios (compras, pagamentos, dívidas, transferências, estornos), o valor ATUAL de cada medida das metas é
 * exatamente o da fonte da tela correspondente (visão de dívidas, dashboard, resumo de recorrências) e as fontes são lidas uma vez só.
 */
@DisplayName("Medidas das metas = fonte de cada tela (ADR-016, PR 01 regra 10)")
class GoalMetricSourcesPropertyTest {

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 40);
    }

    @ParameterizedTest(name = "semente {0}")
    @MethodSource("seeds")
    @DisplayName("current de cada medida é igual à fonte; as sete medidas leem recorrências uma vez só")
    void deveIgualarAsFontes(long seed) {
        Random random = new Random(seed);
        FinanceScenarioFactory s = umCenarioBase();
        LocalDate today = LocalDate.of(2026, 10, 1);
        for (int step = 0; step < 60; step++) {
            today = today.plusDays(random.nextInt(4));
            PropriedadesRazaoAleatorioTest.apply(s, random, today, today.atTime(15, 0).toInstant(ZoneOffset.UTC));
            FinancialRecords records = s.records();
            AtomicInteger recurrenceReads = new AtomicInteger();
            Map<GoalMetric, Money> values = GoalMetricValues.compute(EnumSet.allOf(GoalMetric.class), records, () -> {
                recurrenceReads.incrementAndGet();
                return List.of();
            }, today);

            DebtOverview overview = DebtOverviewCalculator.overview(records, Map.of(), new MonthlyEstimate(Money.zero(CurrencyCode.BRL), Money.zero(CurrencyCode.BRL), true),
                    Money.zero(CurrencyCode.BRL), Money.zero(CurrencyCode.BRL), Money.zero(CurrencyCode.BRL), today, 12);
            DashboardSummary dashboard = DashboardSummary.of(records, List.of(), today, today);
            String ctx = "semente " + seed + ", passo " + step;
            assertThat(values.get(GoalMetric.TOTAL_DEBT)).as(ctx + " total").isEqualTo(overview.total());
            assertThat(values.get(GoalMetric.CARD_DEBT)).as(ctx + " cartão").isEqualTo(overview.parts().cards());
            assertThat(values.get(GoalMetric.PEOPLE_DEBT)).as(ctx + " pessoas").isEqualTo(overview.parts().people());
            assertThat(values.get(GoalMetric.OTHER_DEBT)).as(ctx + " outras").isEqualTo(overview.parts().other());
            assertThat(values.get(GoalMetric.TOTAL_DEBT)).as(ctx + " sem dupla contagem")
                    .isEqualTo(values.get(GoalMetric.CARD_DEBT).add(values.get(GoalMetric.PEOPLE_DEBT)).add(values.get(GoalMetric.OTHER_DEBT)));
            assertThat(values.get(GoalMetric.ACCOUNTS_BALANCE)).as(ctx + " em contas").isEqualTo(dashboard.balances().inAccounts());
            assertThat(values.get(GoalMetric.AVAILABLE_BALANCE)).as(ctx + " disponível").isEqualTo(dashboard.balances().available());
            assertThat(values.get(GoalMetric.FIXED_COSTS_MONTHLY)).as(ctx + " custo fixo").isEqualTo(MonthlyEstimate.of(List.of(), today).fixedCosts());
            assertThat(recurrenceReads.get()).as(ctx + " recorrências lidas uma vez").isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "semente {0}")
    @MethodSource("seeds")
    @DisplayName("só medidas de dívida não leem recorrências; sem medidas pedidas nada é calculado")
    void deveCalcularSoOQuePediu(long seed) {
        FinanceScenarioFactory s = umCenarioBase();
        s.compraNoCartao("100.00", 3);
        AtomicInteger reads = new AtomicInteger();
        GoalMetricValues.compute(EnumSet.of(GoalMetric.TOTAL_DEBT, GoalMetric.CARD_DEBT), s.records(), () -> {
            reads.incrementAndGet();
            return List.of();
        }, LocalDate.of(2026, 10, 10));
        assertThat(reads.get()).isZero();
        assertThat(GoalMetricValues.compute(EnumSet.noneOf(GoalMetric.class), s.records(), () -> {
            reads.incrementAndGet();
            return List.of();
        }, LocalDate.of(2026, 10, 10))).isEmpty();
        assertThat(reads.get()).isZero();
    }
}
