package com.moneylog.core.goal.domain;

import static com.moneylog.core.goal.util.GoalTestFactory.GOAL_ID;
import static com.moneylog.core.goal.util.GoalTestFactory.NOW;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.goal.exception.GoalExceptions.GoalActionLimitException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalActionNotFoundException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalCheckInPeriodInvalidException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

@DisplayName("Estágio, plano automático, plano declarado e consistência (ADR-016)")
class GoalPlanningTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private static Money m(String value) {
        return Money.of(value);
    }

    private static GoalPlanner.Track debt() {
        return new GoalPlanner.Track(true, m("1200.00"), LocalDate.of(2026, 10, 1), m("0.00"), LocalDate.of(2027, 9, 30));
    }

    @Test
    @DisplayName("trajetória linear: 100,00 por mês; marco do mês atual, ritmo necessário e adiantado/atrasado")
    void devePlanejar() {
        GoalPlan plan = GoalPlanner.plan(debt(), m("1100.00"), TODAY);
        assertThat(plan.monthsLeft()).isEqualTo(12);
        assertThat(plan.nextMilestone()).isEqualTo(m("1100.00"));
        assertThat(plan.requiredPace()).isEqualTo(m("91.67"));
        assertThat(plan.onTrack()).isTrue();
        assertThat(plan.gap()).isEqualTo(m("100.00"));
        GoalPlan behind = GoalPlanner.plan(debt(), m("1300.00"), TODAY);
        assertThat(behind.onTrack()).isFalse();
        assertThat(behind.gap()).isEqualTo(m("-100.00"));
    }

    @Test
    @DisplayName("prazo vencido: monthsLeft 0 e ritmo nulo (sem divisão por zero); atingida: ritmo 0,00 e no ritmo")
    void deveTratarPrazoVencidoEMetaAtingida() {
        GoalPlan late = GoalPlanner.plan(debt(), m("500.00"), LocalDate.of(2027, 11, 1));
        assertThat(late.monthsLeft()).isZero();
        assertThat(late.requiredPace()).isNull();
        assertThat(late.nextMilestone()).isEqualTo(m("0.00"));
        GoalPlan done = GoalPlanner.plan(debt(), m("0.00"), LocalDate.of(2027, 11, 1));
        assertThat(done.requiredPace().isZero()).isTrue();
        assertThat(done.onTrack()).isTrue();
        assertThat(GoalPlanner.plan(new GoalPlanner.Track(true, m("10.00"), TODAY, m("0.00"), null), m("5.00"), TODAY)).isNull();
        assertThat(GoalPlanner.plan(null, m("5.00"), TODAY)).isNull();
    }

    @Test
    @DisplayName("consistência: mês sem instantâneo é 'sem dado' (nunca cumprido), quebra a sequência e fica fora da taxa")
    void deveCalcularConsistencia() {
        GoalPlanner.Track track = new GoalPlanner.Track(true, m("1200.00"), LocalDate.of(2026, 5, 1), m("0.00"), LocalDate.of(2027, 4, 30));
        Map<Competence, Money> snaps = new HashMap<>();
        snaps.put(Competence.of(2026, 5), m("1100.00"));
        snaps.put(Competence.of(2026, 7), m("900.00"));
        snaps.put(Competence.of(2026, 8), m("800.00"));
        snaps.put(Competence.of(2026, 9), m("700.00"));
        GoalConsistency c = GoalPlanner.consistency(track, m("650.00"), TODAY, snaps::get);
        assertThat(c.history()).hasSize(6);
        assertThat(c.history().get(1).met()).isNull();
        assertThat(c.history().get(1).actual()).isNull();
        assertThat(c.history().get(5).closed()).isFalse();
        assertThat(c.streak()).isEqualTo(3);
        assertThat(c.rate()).isEqualByComparingTo("1.0000");
        GoalConsistency failing = GoalPlanner.consistency(track, m("650.00"), TODAY, month -> m("1190.00"));
        assertThat(failing.streak()).isZero();
        assertThat(failing.rate()).isEqualByComparingTo("0.0000");
        assertThat(GoalPlanner.consistency(new GoalPlanner.Track(true, m("1.00"), TODAY, m("0.00"), null), m("1.00"), TODAY, x -> null).history()).isEmpty();
    }

    @Test
    @DisplayName("estágio: sem prazo FANTASY; prazo OBJECTIVE; prazo+plano STRATEGY; +2 meses CONSISTENT; atingida prevalece; sem plano declarado nunca passa de OBJECTIVE")
    void deveDerivarEstagio() {
        assertThat(GoalStage.derive(false, false, false, false, 0)).isEqualTo(GoalStage.FANTASY);
        assertThat(GoalStage.derive(false, false, false, true, 5)).isEqualTo(GoalStage.FANTASY);
        assertThat(GoalStage.derive(false, true, true, false, 5)).isEqualTo(GoalStage.OBJECTIVE);
        assertThat(GoalStage.derive(false, true, false, true, 5)).isEqualTo(GoalStage.OBJECTIVE);
        assertThat(GoalStage.derive(false, true, true, true, 1)).isEqualTo(GoalStage.STRATEGY);
        assertThat(GoalStage.derive(false, true, true, true, 2)).isEqualTo(GoalStage.CONSISTENT);
        assertThat(GoalStage.derive(true, false, false, false, 0)).isEqualTo(GoalStage.ACHIEVED);
    }

    @Test
    @DisplayName("propriedade: estágio é monotônico em prazo -> plano -> consistência; ritmo × meses ≈ distância; desligar o prazo rebaixa para FANTASY")
    void devePreservarPropriedades() {
        Random random = new Random(7L);
        for (int i = 0; i < 400; i++) {
            boolean deadline = random.nextBoolean();
            boolean auto = deadline && random.nextBoolean();
            boolean declared = random.nextBoolean();
            int streak = random.nextInt(5);
            GoalStage base = GoalStage.derive(false, deadline, auto, declared, streak);
            assertThat(GoalStage.derive(false, true, auto || deadline && random.nextBoolean(), declared, streak).ordinal())
                    .isGreaterThanOrEqualTo(deadline ? base.ordinal() : GoalStage.FANTASY.ordinal());
            assertThat(GoalStage.derive(false, deadline, auto, true, streak).ordinal()).isGreaterThanOrEqualTo(base.ordinal());
            assertThat(GoalStage.derive(false, deadline, auto, declared, streak + 1).ordinal()).isGreaterThanOrEqualTo(base.ordinal());
            assertThat(GoalStage.derive(false, false, false, declared, streak)).isEqualTo(GoalStage.FANTASY);
            assertThat(GoalStage.derive(true, deadline, auto, declared, streak)).isEqualTo(GoalStage.ACHIEVED);
        }
        for (int i = 0; i < 300; i++) {
            long start = 100_000 + random.nextInt(900_000);
            long distance = 1 + random.nextInt(500_000);
            int months = 1 + random.nextInt(36);
            LocalDate deadline = TODAY.plusMonths(months - 1L).withDayOfMonth(15);
            GoalPlanner.Track track = new GoalPlanner.Track(false, Money.ofCents(start, CurrencyCode.BRL), TODAY, Money.ofCents(start + distance, CurrencyCode.BRL), deadline);
            GoalPlan plan = GoalPlanner.plan(track, Money.ofCents(start, CurrencyCode.BRL), TODAY);
            assertThat(plan.monthsLeft()).isEqualTo(months);
            long product = plan.requiredPace().cents() * plan.monthsLeft();
            assertThat(product).isBetween(distance, distance + plan.monthsLeft() - 1L);
            assertThat(plan.nextMilestone().cents()).isBetween(start, start + distance);
        }
    }

    @Test
    @DisplayName("a trajetória é linear, cresce até o alvo e termina exatamente nele no mês do prazo")
    void deveTerminarNoAlvo() {
        GoalPlanner.Track track = new GoalPlanner.Track(false, m("0.00"), LocalDate.of(2026, 10, 3), m("1000.00"), LocalDate.of(2027, 1, 20));
        assertThat(GoalPlanner.expectedAtEndOf(track, Competence.of(2026, 9)).isZero()).isTrue();
        assertThat(GoalPlanner.expectedAtEndOf(track, Competence.of(2026, 10))).isEqualTo(m("250.00"));
        assertThat(GoalPlanner.expectedAtEndOf(track, Competence.of(2027, 1))).isEqualTo(m("1000.00"));
        assertThat(GoalPlanner.expectedAtEndOf(track, Competence.of(2028, 1))).isEqualTo(m("1000.00"));
    }

    @Test
    @DisplayName("plano declarado: até 10 ações, check-in idempotente por período válido, frequência nova apaga check-ins, arquivada recusa")
    void deveGerirPlanoDeclarado() {
        Goal g = Goal.create(GOAL_ID, USER_ID, "Viagem", m("1000.00"), LocalDate.of(2027, 12, 31), GoalPriority.LOW, null);
        assertThat(g.hasDeclaredPlan()).isFalse();
        UUID id = g.addAction(UUID.randomUUID(), " Guardar todo mês ", GoalActionFrequency.MONTHLY, NOW).id();
        assertThat(g.actions().getFirst().title()).isEqualTo("Guardar todo mês");
        assertThat(g.hasDeclaredPlan()).isTrue();
        g.checkIn(id, "2026-10", true);
        g.checkIn(id, "2026-10", true);
        assertThat(g.actions().getFirst().doneIn()).containsExactly("2026-10");
        assertThatThrownBy(() -> g.checkIn(id, "2026-W41", true)).isInstanceOf(GoalCheckInPeriodInvalidException.class);
        assertThatThrownBy(() -> g.checkIn(id, null, true)).isInstanceOf(GoalCheckInPeriodInvalidException.class);
        g.checkIn(id, "2026-10", false);
        assertThat(g.actions().getFirst().doneIn()).isEmpty();
        g.checkIn(id, "2026-11", true);
        g.updateAction(id, "Guardar toda semana", GoalActionFrequency.WEEKLY);
        assertThat(g.actions().getFirst().doneIn()).isEmpty();
        g.checkIn(id, "2026-W41", true);
        assertThat(g.actions().getFirst().doneIn()).containsExactly("2026-W41");
        UUID once = g.addAction(UUID.randomUUID(), "Abrir conta", GoalActionFrequency.ONE_TIME, NOW).id();
        g.checkIn(once, null, true);
        assertThat(g.actions().get(1).doneIn()).containsExactly("ONCE");
        assertThatThrownBy(() -> g.checkIn(UUID.randomUUID(), "2026-10", true)).isInstanceOf(GoalActionNotFoundException.class);
        for (int i = 2; i < Goal.MAX_ACTIONS; i++) {
            g.addAction(UUID.randomUUID(), "a" + i, GoalActionFrequency.ONE_TIME, NOW);
        }
        assertThatThrownBy(() -> g.addAction(UUID.randomUUID(), "demais", GoalActionFrequency.ONE_TIME, NOW)).isInstanceOf(GoalActionLimitException.class);
        g.removeAction(once);
        assertThat(g.actions()).hasSize(Goal.MAX_ACTIONS - 1);
        g.declarePlanNote("  Todo dia 5  ");
        assertThat(g.planNote()).isEqualTo("Todo dia 5");
        g.declarePlanNote(" ");
        assertThat(g.planNote()).isNull();
        g.archive();
        assertThatThrownBy(() -> g.checkIn(id, "2026-W42", true)).isInstanceOf(GoalArchivedException.class);
        assertThatThrownBy(() -> g.declarePlanNote("x")).isInstanceOf(GoalArchivedException.class);
    }

    @Test
    @DisplayName("trajetória por tipo: poupar parte de 0,00 na criação; medida usa a baseline; quitar dívidas só depois de ancorar")
    void deveMontarTrajetoria() {
        Goal savings = Goal.create(GOAL_ID, USER_ID, "Viagem", m("1000.00"), LocalDate.of(2027, 1, 1), GoalPriority.LOW, null);
        GoalPlanner.Track s = savings.track(LocalDate.of(2026, 10, 1), ZONE);
        assertThat(s.decrease()).isFalse();
        assertThat(s.start().isZero()).isTrue();
        Goal metric = Goal.createMetric(GOAL_ID, USER_ID, null, GoalMetric.CARD_DEBT, m("0.00"), m("900.00"), NOW, LocalDate.of(2027, 1, 1), GoalPriority.LOW, null);
        assertThat(metric.track(LocalDate.of(2026, 10, 1), ZONE).decrease()).isTrue();
        assertThat(metric.track(LocalDate.of(2026, 10, 1), ZONE).start()).isEqualTo(m("900.00"));
        Goal payoff = Goal.createDebtPayoff(GOAL_ID, USER_ID, "Quitar", LocalDate.of(2027, 1, 1), GoalPriority.LOW, null);
        assertThat(payoff.track(LocalDate.of(2026, 10, 1), ZONE)).isNull();
        assertThat(payoff.hasAutoPlan()).isFalse();
        payoff.anchorBaseline(m("500.00"), NOW);
        assertThat(payoff.hasAutoPlan()).isTrue();
        assertThat(payoff.track(LocalDate.of(2026, 10, 1), ZONE).target().isZero()).isTrue();
    }
}
