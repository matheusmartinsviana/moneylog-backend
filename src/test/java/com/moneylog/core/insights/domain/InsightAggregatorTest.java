package com.moneylog.core.insights.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.insights.domain.Dismissal.Scope;
import com.moneylog.core.insights.domain.Insight.Period;
import com.moneylog.core.insights.domain.Insight.Severity;

@DisplayName("Agregador (T293): ordem estável, deduplicação, id por usuário e dispensas")
class InsightAggregatorTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final Instant NOW = Instant.parse("2026-10-17T15:00:00Z");

    private static Insight insight(String type, Severity severity, int priority, long magnitude, String subject, Period period, List<String> refs) {
        return new Insight(type, "insight.test", Map.of(), refs, severity, priority, period, null, null, subject, magnitude);
    }

    private static Insight day(String type, Severity severity, int priority, long magnitude) {
        return insight(type, severity, priority, magnitude, null, new Period(Period.Kind.DAY, "2026-10-17"), List.of());
    }

    @Test
    @DisplayName("dois ALERT e um INFO: ALERT por prioridade e magnitude, depois INFO, estável")
    void deveOrdenarPorSeveridadePrioridadeEMagnitude() {
        Insight info = day("DAILY_BUDGET", Severity.INFO, 100, 5);
        Insight alertLow = day("DUE_SOON", Severity.ALERT, 85, 999);
        Insight alertHigh = day("MIN_BALANCE_FORECAST", Severity.ALERT, 95, 1);
        Insight attention = day("TODAY_VS_AVERAGE", Severity.ATTENTION, 70, 1);

        List<InsightItem> out = InsightAggregator.aggregate(USER, List.of(info, alertLow, attention, alertHigh), List.of(), TODAY, false);

        assertThat(out).extracting(i -> i.insight().type()).containsExactly("MIN_BALANCE_FORECAST", "DUE_SOON", "TODAY_VS_AVERAGE", "DAILY_BUDGET");
        assertThat(InsightAggregator.aggregate(USER, List.of(alertHigh, attention, info, alertLow), List.of(), TODAY, false))
                .isEqualTo(out);
    }

    @Test
    @DisplayName("mesma severidade e prioridade: a maior magnitude vem primeiro; empate final por tipo e id")
    void deveDesempatarPorMagnitudeETipo() {
        Insight small = day("B_TYPE", Severity.ATTENTION, 50, 10);
        Insight big = day("C_TYPE", Severity.ATTENTION, 50, 900);
        Insight tie = day("A_TYPE", Severity.ATTENTION, 50, 10);

        assertThat(InsightAggregator.aggregate(USER, List.of(small, big, tie), List.of(), TODAY, false))
                .extracting(i -> i.insight().type()).containsExactly("C_TYPE", "A_TYPE", "B_TYPE");
    }

    @Test
    @DisplayName("o id é ins_ + 32 hexadecimais, estável, e muda com o usuário (id de outro usuário não existe)")
    void deveGerarIdPorUsuario() {
        Insight a = day("DAILY_BUDGET", Severity.INFO, 100, 1);
        UUID other = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");

        String id = InsightIds.id(USER, a);

        assertThat(id).matches("ins_[0-9a-f]{32}").isEqualTo(InsightIds.id(USER, a)).isNotEqualTo(InsightIds.id(other, a));
        assertThat(InsightIds.fingerprintOf(id)).hasSize(32);
        assertThat(InsightIds.fingerprintOf("ins_xyz")).isNull();
        assertThat(InsightIds.fingerprintOf(null)).isNull();
        Insight otherDay = insight("DAILY_BUDGET", Severity.INFO, 100, 1, null, new Period(Period.Kind.DAY, "2026-10-18"), List.of());
        assertThat(InsightIds.id(USER, otherDay)).isNotEqualTo(id);
    }

    @Test
    @DisplayName("deduplica por id e suprime o CARD_CLOSING_SOON de uma fatura que já está no DUE_SOON")
    void deveDeduplicar() {
        Insight due = insight("DUE_SOON", Severity.INFO, 85, 100, null, new Period(Period.Kind.DAY, "2026-10-17"), List.of("commitments", "invoice.a.2026-10-10"));
        Insight closingSame = insight("CARD_CLOSING_SOON", Severity.INFO, 45, 100, "invoice.a.2026-10-10", new Period(Period.Kind.DAY, "2026-10-17"),
                List.of("invoice.a.2026-10-10"));
        Insight closingOther = insight("CARD_CLOSING_SOON", Severity.INFO, 45, 100, "invoice.b.2026-10-12", new Period(Period.Kind.DAY, "2026-10-17"),
                List.of("invoice.b.2026-10-12"));

        List<InsightItem> out = InsightAggregator.aggregate(USER, List.of(due, closingSame, closingOther, due), List.of(), TODAY, false);

        assertThat(out).extracting(i -> i.insight().subjectId() + "|" + i.insight().type())
                .containsExactlyInAnyOrder("null|DUE_SOON", "invoice.b.2026-10-12|CARD_CLOSING_SOON");
    }

    @Test
    @DisplayName("dispensar HOJE esconde o insight hoje e ele volta no dia seguinte; includeDismissed devolve com dismissed = true")
    void deveEsconderPorUmDia() {
        Insight budget = day("DAILY_BUDGET", Severity.INFO, 100, 1);
        String fingerprint = InsightIds.fingerprint(USER, budget);
        Dismissal today = Dismissal.of(UUID.randomUUID(), USER, budget, fingerprint, Scope.TODAY, TODAY, NOW);

        assertThat(InsightAggregator.aggregate(USER, List.of(budget), List.of(today), TODAY, false)).isEmpty();
        assertThat(InsightAggregator.aggregate(USER, List.of(budget), List.of(today), TODAY, true)).singleElement().extracting(InsightItem::dismissed)
                .isEqualTo(true);
        assertThat(InsightAggregator.aggregate(USER, List.of(budget), List.of(today), TODAY.plusDays(1), false)).hasSize(1);
    }

    @Test
    @DisplayName("MONTH esconde o mesmo tipo e assunto até o fim do mês (insight de outro dia do mesmo mês também); TYPE esconde o tipo todo")
    void deveEsconderPorMesEPorTipo() {
        Insight yesterdayBudget = insight("DAILY_BUDGET", Severity.INFO, 100, 1, null, new Period(Period.Kind.DAY, "2026-10-16"), List.of());
        Insight todayBudget = day("DAILY_BUDGET", Severity.INFO, 100, 1);
        Dismissal month = Dismissal.of(UUID.randomUUID(), USER, yesterdayBudget, InsightIds.fingerprint(USER, yesterdayBudget), Scope.MONTH,
                TODAY.minusDays(1), NOW);

        assertThat(InsightAggregator.aggregate(USER, List.of(todayBudget), List.of(month), TODAY, false)).isEmpty();
        assertThat(InsightAggregator.aggregate(USER, List.of(todayBudget), List.of(month), LocalDate.of(2026, 11, 1), false)).hasSize(1);

        Insight foodA = insight("CATEGORY_DELTA", Severity.ATTENTION, 60, 1, "cat-a", new Period(Period.Kind.MONTH, "2026-10"), List.of());
        Insight foodB = insight("CATEGORY_DELTA", Severity.ATTENTION, 60, 1, "cat-b", new Period(Period.Kind.MONTH, "2026-10"), List.of());
        Dismissal onlyA = Dismissal.of(UUID.randomUUID(), USER, foodA, InsightIds.fingerprint(USER, foodA), Scope.MONTH, TODAY, NOW);
        assertThat(InsightAggregator.aggregate(USER, List.of(foodA, foodB), List.of(onlyA), TODAY, false)).extracting(i -> i.insight().subjectId())
                .containsExactly("cat-b");

        Dismissal type = Dismissal.of(UUID.randomUUID(), USER, foodA, InsightIds.fingerprint(USER, foodA), Scope.TYPE, TODAY.minusDays(40), NOW);
        assertThat(InsightAggregator.aggregate(USER, List.of(foodA, foodB), List.of(type), TODAY, false)).isEmpty();
    }

    @Test
    @DisplayName("dispensa revogada não esconde nada")
    void naoDeveEsconderComDispensaRevogada() {
        Insight budget = day("DAILY_BUDGET", Severity.INFO, 100, 1);
        Dismissal active = Dismissal.of(UUID.randomUUID(), USER, budget, InsightIds.fingerprint(USER, budget), Scope.TYPE, TODAY, NOW);
        Dismissal revoked = new Dismissal(active.id(), USER, active.fingerprint(), active.type(), null, Scope.TYPE, TODAY, NOW, NOW.plusSeconds(60));

        assertThat(revoked.isActive()).isFalse();
        assertThat(InsightAggregator.aggregate(USER, List.of(budget), List.of(revoked), TODAY, false)).hasSize(1);
    }
}
