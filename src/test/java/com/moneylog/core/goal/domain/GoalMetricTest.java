package com.moneylog.core.goal.domain;

import static com.moneylog.core.goal.util.GoalTestFactory.GOAL_ID;
import static com.moneylog.core.goal.util.GoalTestFactory.NOW;
import static com.moneylog.core.goal.util.GoalTestFactory.TODAY;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.goal.exception.GoalExceptions.GoalKindMismatchException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalKindNotReservableException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalMetricTargetEqualsBaselineException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalMetricTargetNegativeException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalStateInconsistentException;
import com.moneylog.core.sk.money.Money;

@DisplayName("Meta de medida (ADR-016): direção derivada, progresso 0..1 e invariantes")
class GoalMetricTest {

    private static Goal metric(GoalMetric metric, String target, String baseline, String name) {
        return Goal.createMetric(GOAL_ID, USER_ID, name, metric, Money.of(target), Money.of(baseline), NOW, null, GoalPriority.MEDIUM, null);
    }

    @Test
    @DisplayName("alvo abaixo da baseline é DECREASE; acima é INCREASE; igual é recusado (GOAL_METRIC_TARGET_EQUALS_BASELINE)")
    void deveDerivarADirecao() {
        assertThat(metric(GoalMetric.TOTAL_DEBT, "0.00", "5000.00", null).direction()).isEqualTo(GoalDirection.DECREASE);
        assertThat(metric(GoalMetric.ACCOUNTS_BALANCE, "10000.00", "2500.00", "Colchão").direction()).isEqualTo(GoalDirection.INCREASE);
        assertThatThrownBy(() -> metric(GoalMetric.CARD_DEBT, "300.00", "300.00", null)).isInstanceOf(GoalMetricTargetEqualsBaselineException.class);
        assertThatThrownBy(() -> metric(GoalMetric.CARD_DEBT, "-1.00", "300.00", null)).isInstanceOf(GoalMetricTargetNegativeException.class);
    }

    @Test
    @DisplayName("nome é opcional (nulo/branco vira nulo) e baseline pode ser zero ou negativa (disponível no vermelho)")
    void deveAceitarSemNomeEBaselineNegativa() {
        assertThat(metric(GoalMetric.AVAILABLE_BALANCE, "500.00", "-200.00", "  ").name()).isNull();
        Goal g = metric(GoalMetric.AVAILABLE_BALANCE, "500.00", "-200.00", null);
        assertThat(g.direction()).isEqualTo(GoalDirection.INCREASE);
        assertThat(g.metricProgress(Money.of("150.00")).ratio()).isEqualByComparingTo("0.5000");
        assertThat(g.baselineAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("DECREASE: (baseline - atual) / (baseline - alvo), com clamp 0..1; distante do alvo não passa de 0,9999 e 1,0000 só quando atingida")
    void deveCalcularProgressoDeQueda() {
        Goal g = metric(GoalMetric.TOTAL_DEBT, "1000.00", "5000.00", null);
        assertThat(g.metricProgress(Money.of("5000.00")).ratio()).isEqualByComparingTo("0.0000");
        assertThat(g.metricProgress(Money.of("4000.00")).ratio()).isEqualByComparingTo("0.2500");
        assertThat(g.metricProgress(Money.of("4000.00")).remaining()).isEqualTo(Money.of("3000.00"));
        assertThat(g.metricProgress(Money.of("7000.00")).ratio()).isEqualByComparingTo("0.0000");
        MetricProgress almost = g.metricProgress(Money.of("1000.01"));
        assertThat(almost.reached()).isFalse();
        assertThat(almost.ratio()).isEqualByComparingTo("0.9999");
        MetricProgress done = g.metricProgress(Money.of("900.00"));
        assertThat(done.reached()).isTrue();
        assertThat(done.ratio()).isEqualByComparingTo("1.0000");
        assertThat(done.remaining().isZero()).isTrue();
        assertThat(g.metricStatus(done)).isEqualTo(GoalStatus.ACHIEVED);
    }

    @Test
    @DisplayName("INCREASE: (atual - baseline) / (alvo - baseline), com clamp 0..1")
    void deveCalcularProgressoDeAlta() {
        Goal g = metric(GoalMetric.ACCOUNTS_BALANCE, "10000.00", "2000.00", "Reserva");
        assertThat(g.metricProgress(Money.of("6000.00")).ratio()).isEqualByComparingTo("0.5000");
        assertThat(g.metricProgress(Money.of("1000.00")).ratio()).isEqualByComparingTo("0.0000");
        assertThat(g.metricProgress(Money.of("12000.00")).reached()).isTrue();
        assertThat(g.metricProgress(Money.of("12000.00")).ratio()).isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("propriedade: progresso fica em [0,1] e é monotônico na direção da meta; 1,0000 se e somente se atingida")
    void deveSerMonotonicoELimitado() {
        Random random = new Random(20261010L);
        for (int i = 0; i < 300; i++) {
            long baseline = random.nextInt(2_000_000) - 100_000;
            long target = Math.max(0, baseline + (random.nextBoolean() ? 1 : -1) * (1 + random.nextInt(1_500_000)));
            if (target == baseline) {
                continue;
            }
            Goal g = Goal.createMetric(GOAL_ID, USER_ID, null, GoalMetric.TOTAL_DEBT, Money.ofCents(target, com.moneylog.core.sk.money.CurrencyCode.BRL),
                    Money.ofCents(baseline, com.moneylog.core.sk.money.CurrencyCode.BRL), NOW, null, GoalPriority.LOW, null);
            boolean decrease = g.direction() == GoalDirection.DECREASE;
            BigDecimal previous = null;
            for (int step = -3_000_000; step <= 3_000_000; step += 37_000) {
                long value = decrease ? -step : step;
                MetricProgress p = g.metricProgress(Money.ofCents(value, com.moneylog.core.sk.money.CurrencyCode.BRL));
                assertThat(p.ratio()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
                assertThat(p.ratio().compareTo(BigDecimal.ONE) == 0).isEqualTo(p.reached());
                assertThat(p.remaining().isNegative()).isFalse();
                if (previous != null) {
                    assertThat(p.ratio()).isGreaterThanOrEqualTo(previous);
                }
                previous = p.ratio();
            }
        }
    }

    @Test
    @DisplayName("re-ancorar: nova baseline e direção; alvo igual ao valor atual é recusado e não altera a meta; arquivada não re-ancora")
    void deveReancorar() {
        Goal g = metric(GoalMetric.TOTAL_DEBT, "1000.00", "5000.00", null);
        g.anchorBaseline(Money.of("3000.00"), NOW.plusSeconds(60));
        assertThat(g.baseline()).isEqualTo(Money.of("3000.00"));
        assertThat(g.baselineAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(g.direction()).isEqualTo(GoalDirection.DECREASE);
        g.anchorBaseline(Money.of("200.00"), NOW.plusSeconds(120));
        assertThat(g.direction()).isEqualTo(GoalDirection.INCREASE);
        assertThatThrownBy(() -> g.anchorBaseline(Money.of("1000.00"), NOW)).isInstanceOf(GoalMetricTargetEqualsBaselineException.class);
        assertThat(g.baseline()).isEqualTo(Money.of("200.00"));
        g.archive();
        assertThatThrownBy(() -> g.anchorBaseline(Money.of("1.00"), NOW)).isInstanceOf(com.moneylog.core.goal.exception.GoalExceptions.GoalArchivedException.class);
    }

    @Test
    @DisplayName("editar o alvo re-deriva a direção; alvo igual à baseline é recusado; sem reservas; meta de poupar não ancora baseline")
    void deveEditarEProtegerTipos() {
        Goal g = metric(GoalMetric.PEOPLE_DEBT, "0.00", "800.00", null);
        g.update(null, Money.of("2000.00"), LocalDate.of(2027, 1, 1), GoalPriority.HIGH, null);
        assertThat(g.direction()).isEqualTo(GoalDirection.INCREASE);
        assertThatThrownBy(() -> g.update(null, Money.of("800.00"), null, GoalPriority.HIGH, null)).isInstanceOf(GoalMetricTargetEqualsBaselineException.class);
        assertThat(g.target()).isEqualTo(Money.of("2000.00"));
        assertThatThrownBy(() -> g.reserve(java.util.UUID.randomUUID(), null, TODAY, null, NOW)).isInstanceOf(GoalKindNotReservableException.class);
        Goal savings = Goal.create(GOAL_ID, USER_ID, "Viagem", Money.of("100.00"), null, GoalPriority.LOW, null);
        assertThatThrownBy(() -> savings.anchorBaseline(Money.of("1.00"), NOW)).isInstanceOf(GoalKindMismatchException.class);
    }

    @Test
    @DisplayName("reidratar estado incoerente (direção que não bate, reserva em meta de medida) vira inconsistência interna")
    void deveRecusarEstadoIncoerente() {
        assertThatThrownBy(() -> Goal.restore(GOAL_ID, USER_ID, null, Money.of("0.00"), null, GoalPriority.LOW, null, false, GoalKind.METRIC,
                Money.of("10.00"), NOW, GoalMetric.TOTAL_DEBT, GoalDirection.INCREASE, List.of())).isInstanceOf(GoalStateInconsistentException.class);
        assertThatThrownBy(() -> Goal.restore(GOAL_ID, USER_ID, "x", Money.of("10.00"), null, GoalPriority.LOW, null, false, GoalKind.SAVINGS,
                null, null, GoalMetric.TOTAL_DEBT, null, List.of())).isInstanceOf(GoalStateInconsistentException.class);
    }

    @Test
    @DisplayName("monthlyNeeded da medida: distância ÷ meses até o prazo (para cima); atingida 0,00; sem prazo/prazo vencido nulo")
    void deveEstimarRitmoMensal() {
        Goal g = Goal.createMetric(GOAL_ID, USER_ID, null, GoalMetric.TOTAL_DEBT, Money.of("0.00"), Money.of("5000.00"), NOW,
                LocalDate.of(2026, 12, 31), GoalPriority.LOW, null);
        assertThat(g.metricMonthlyNeeded(g.metricProgress(Money.of("5000.00")), TODAY)).isEqualTo(Money.of("1666.67"));
        assertThat(g.metricMonthlyNeeded(g.metricProgress(Money.of("0.00")), TODAY).isZero()).isTrue();
        assertThat(g.metricMonthlyNeeded(g.metricProgress(Money.of("5000.00")), LocalDate.of(2027, 1, 1))).isNull();
        assertThat(metric(GoalMetric.TOTAL_DEBT, "0.00", "5000.00", null).metricMonthlyNeeded(g.metricProgress(Money.of("1.00")), TODAY)).isNull();
    }
}
