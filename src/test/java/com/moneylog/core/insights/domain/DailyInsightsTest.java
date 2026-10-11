package com.moneylog.core.insights.domain;

import static com.moneylog.core.insights.domain.InsightFacts.brl;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.insights.domain.Insight.Severity;
import com.moneylog.core.insights.domain.InsufficientData.Reason;
import com.moneylog.core.sk.money.Money;

@DisplayName("Insights do dia (T291, T292): orçamento, hoje × média, vencimentos, saldo mínimo, atípico e fatura que fecha")
class DailyInsightsTest {

    private static final UUID CAT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static final class Day {
        LocalDate date = LocalDate.of(2026, 10, 17);
        MonthFacts.Budget budget = new MonthFacts.Budget(brl("3000.00"), brl("1600.00"), brl("0.00"), brl("0.00"), brl("0.00"));
        List<Money> before = new ArrayList<>();
        Money today = brl("0.00");
        Money cash = brl("1000.00");
        boolean hasCash = true;
        List<DayFacts.Due> due = new ArrayList<>();
        List<DayFacts.CashEvent> events = new ArrayList<>();
        List<DayFacts.TodayEntry> entries = new ArrayList<>();
        Map<UUID, List<Money>> history = Map.of();
        List<DayFacts.Closing> closings = new ArrayList<>();

        Day date(int y, int m, int d) {
            date = LocalDate.of(y, m, d);
            return this;
        }

        Day budget(String received, String spent) {
            budget = new MonthFacts.Budget(brl(received), brl(spent), brl("0.00"), brl("0.00"), brl("0.00"));
            return this;
        }

        Day before(int days, String each) {
            before = new ArrayList<>();
            for (int i = 0; i < days; i++) {
                before.add(brl(each));
            }
            return this;
        }

        InsightSink run() {
            InsightSink sink = new InsightSink();
            DailyInsights.compute(new DayFacts(date, budget, before, today, cash, hasCash, due, events, entries, history,
                    Map.of(CAT, "Alimentação"), closings), sink);
            return sink;
        }
    }

    private static Insight only(InsightSink sink, String type) {
        return InsightFacts.only(sink, type);
    }

    // ---------------------------------------------------------------- DAILY_BUDGET

    @Test
    @DisplayName("livre restante 1.400,00 no dia 17 de um mês de 31 dias... 15 dias com hoje: orçamento 93,33; no dia 18 de outubro (14 dias): 100,00")
    void deveDividirOLivrePelosDiasRestantesComHoje() {
        // outubro tem 31 dias: dia 18 -> 31 - 18 + 1 = 14 dias; livre = 3000 - 1600 = 1400 -> 100,00
        Insight insight = only(new Day().date(2026, 10, 18).run(), "DAILY_BUDGET");

        assertThat(insight.params()).containsEntry("amount", "100.00").containsEntry("daysLeft", "14").containsEntry("free", "1400.00");
        assertThat(insight.severity()).isEqualTo(Severity.INFO);
        assertThat(insight.key()).isEqualTo("insight.daily.budget");
        assertThat(insight.priority()).isEqualTo(Thresholds.PRIORITY_MAX);
        assertThat(insight.period().kind()).isEqualTo(Insight.Period.Kind.DAY);
        assertThat(insight.period().value()).isEqualTo("2026-10-18");
    }

    @Test
    @DisplayName("arredonda para baixo: 1.000,00 em 3 dias dá 333,33 (nunca promete centavo que não há)")
    void deveArredondarParaBaixo() {
        // 29 de outubro: 31 - 29 + 1 = 3 dias
        assertThat(only(new Day().date(2026, 10, 29).budget("2000.00", "1000.00").run(), "DAILY_BUDGET").params()).containsEntry("amount", "333.33");
    }

    @Test
    @DisplayName("livre restante negativo: ALERT 'estourado' com valor 0,00 e o quanto passou")
    void deveAvisarOrcamentoEstourado() {
        Insight insight = only(new Day().budget("3000.00", "3400.00").run(), "DAILY_BUDGET");

        assertThat(insight.severity()).isEqualTo(Severity.ALERT);
        assertThat(insight.key()).isEqualTo("insight.daily.over");
        assertThat(insight.params()).containsEntry("amount", "0.00").containsEntry("overBy", "400.00");
    }

    @Test
    @DisplayName("dia 31: daysLeft = 1; fevereiro bissexto tem 29 dias; virada de mês não carrega o gasto do mês anterior")
    void deveContarOsDiasDoMes() {
        assertThat(only(new Day().date(2026, 10, 31).run(), "DAILY_BUDGET").params()).containsEntry("daysLeft", "1");
        assertThat(only(new Day().date(2028, 2, 1).run(), "DAILY_BUDGET").params()).containsEntry("daysLeft", "29");
        assertThat(only(new Day().date(2027, 2, 1).run(), "DAILY_BUDGET").params()).containsEntry("daysLeft", "28");
        assertThat(only(new Day().date(2026, 11, 1).run(), "DAILY_BUDGET").params()).containsEntry("daysLeft", "30");
    }

    @Test
    @DisplayName("sem renda recebida no mês: não emite orçamento (insufficient NO_INCOME), nunca estima")
    void naoDeveEmitirSemRendaRecebida() {
        InsightSink sink = new Day().budget("0.00", "100.00").run();

        assertThat(InsightFacts.has(sink, "DAILY_BUDGET")).isFalse();
        assertThat(InsightFacts.insufficient(sink, "DAILY_BUDGET", Reason.NO_INCOME)).isTrue();
    }

    @Test
    @DisplayName("o orçamento nunca é negativo (propriedade em 300 combinações)")
    void orcamentoNuncaNegativo() {
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 300; i++) {
            Day day = new Day().date(2026, 10, 1 + random.nextInt(31))
                    .budget(random.nextInt(900_000) + ".00", random.nextInt(900_000) + ".00");
            Insight insight = only(day.run(), "DAILY_BUDGET");
            assertThat(new java.math.BigDecimal(insight.params().get("amount"))).isGreaterThanOrEqualTo(java.math.BigDecimal.ZERO);
        }
    }

    // ---------------------------------------------------------------- TODAY_VS_AVERAGE

    @Test
    @DisplayName("gasto de hoje 40,00 com média 10,00: não emite (materialidade de R$ 50); 120,00 emite ATTENTION")
    void deveComparerHojeComAMedia() {
        Day small = new Day().before(16, "10.00");
        small.today = brl("40.00");
        assertThat(InsightFacts.has(small.run(), "TODAY_VS_AVERAGE")).isFalse();

        Day big = new Day().before(16, "10.00");
        big.today = brl("120.00");
        Insight insight = only(big.run(), "TODAY_VS_AVERAGE");
        assertThat(insight.severity()).isEqualTo(Severity.ATTENTION);
        assertThat(insight.params()).containsEntry("today", "120.00").containsEntry("average", "10.00").containsEntry("ratio", "12.00");
    }

    @Test
    @DisplayName("limite exato (2x e R$ 50): emite; abaixo de 2x não emite")
    void deveSerInclusivoNoLimite() {
        Day exact = new Day().before(16, "25.00");
        exact.today = brl("50.00");
        assertThat(InsightFacts.has(exact.run(), "TODAY_VS_AVERAGE")).isTrue();
        Day below = new Day().before(16, "26.00");
        below.today = brl("51.00");
        assertThat(InsightFacts.has(below.run(), "TODAY_VS_AVERAGE")).isFalse();
    }

    @Test
    @DisplayName("antes do dia 7 ou com média zero: insufficient")
    void naoDeveEmitirComPoucosDias() {
        Day early = new Day().date(2026, 10, 5).before(4, "10.00");
        early.today = brl("500.00");
        assertThat(InsightFacts.insufficient(early.run(), "TODAY_VS_AVERAGE", Reason.PARTIAL_MONTH)).isTrue();
        Day zero = new Day().before(16, "0.00");
        zero.today = brl("500.00");
        assertThat(InsightFacts.insufficient(zero.run(), "TODAY_VS_AVERAGE", Reason.NO_BASELINE)).isTrue();
    }

    // ---------------------------------------------------------------- DUE_SOON

    @Test
    @DisplayName("vencimentos hoje de 300,00 e saldo líquido de 200,00: ALERT (excede o saldo)")
    void deveAlertarQuandoOVencimentoExcedeOSaldo() {
        Day day = new Day();
        day.cash = brl("200.00");
        day.due.add(new DayFacts.Due(day.date, brl("300.00"), null));

        Insight insight = only(day.run(), "DUE_SOON");

        assertThat(insight.severity()).isEqualTo(Severity.ALERT);
        assertThat(insight.key()).isEqualTo("insight.due.exceeds");
        assertThat(insight.params()).containsEntry("total", "300.00").containsEntry("count", "1").containsEntry("today", "300.00");
    }

    @Test
    @DisplayName("vence hoje com saldo suficiente: ATTENTION; só na semana: INFO")
    void deveClassificarOsVencimentos() {
        Day today = new Day();
        today.due.add(new DayFacts.Due(today.date, brl("300.00"), null));
        assertThat(only(today.run(), "DUE_SOON").severity()).isEqualTo(Severity.ATTENTION);

        Day week = new Day();
        week.due.add(new DayFacts.Due(week.date.plusDays(3), brl("300.00"), null));
        week.due.add(new DayFacts.Due(week.date.plusDays(5), brl("50.00"), null));
        Insight insight = only(week.run(), "DUE_SOON");
        assertThat(insight.severity()).isEqualTo(Severity.INFO);
        assertThat(insight.params()).containsEntry("total", "350.00").containsEntry("count", "2").containsEntry("today", "0.00");
    }

    @Test
    @DisplayName("sem nada vencendo na semana: não emite")
    void naoDeveEmitirSemVencimentos() {
        assertThat(InsightFacts.has(new Day().run(), "DUE_SOON")).isFalse();
    }

    // ---------------------------------------------------------------- MIN_BALANCE_FORECAST

    @Test
    @DisplayName("saldo 500,00 e saída agendada de 800,00 em 5 dias: ALERT com firstNegativeDate = hoje + 5 e minBalance = -300,00")
    void deveAlertarSaldoNegativoEmBreve() {
        Day day = new Day();
        day.cash = brl("500.00");
        day.events.add(new DayFacts.CashEvent(day.date.plusDays(5), brl("-800.00")));

        Insight insight = only(day.run(), "MIN_BALANCE_FORECAST");

        assertThat(insight.severity()).isEqualTo(Severity.ALERT);
        assertThat(insight.key()).isEqualTo("insight.balance.negativeSoon");
        assertThat(insight.params()).containsEntry("minBalance", "-300.00").containsEntry("firstNegativeDate", "2026-10-22")
                .containsEntry("minDate", "2026-10-22").containsEntry("daysUntil", "5");
        assertThat(insight.basis()).isEqualTo(Insight.Basis.KNOWN_SCHEDULE);
    }

    @Test
    @DisplayName("entrada conhecida antes da saída evita o negativo; saída depois de 7 dias é ATTENTION; fora de 30 dias não conta")
    void deveSomarEntradasConhecidas() {
        Day covered = new Day();
        covered.cash = brl("500.00");
        covered.events.add(new DayFacts.CashEvent(covered.date.plusDays(3), brl("1000.00")));
        covered.events.add(new DayFacts.CashEvent(covered.date.plusDays(5), brl("-800.00")));
        assertThat(InsightFacts.has(covered.run(), "MIN_BALANCE_FORECAST")).isFalse();

        Day later = new Day();
        later.cash = brl("500.00");
        later.events.add(new DayFacts.CashEvent(later.date.plusDays(20), brl("-800.00")));
        assertThat(only(later.run(), "MIN_BALANCE_FORECAST").severity()).isEqualTo(Severity.ATTENTION);
        assertThat(only(later.run(), "MIN_BALANCE_FORECAST").key()).isEqualTo("insight.balance.negativeLater");

        Day far = new Day();
        far.cash = brl("500.00");
        far.events.add(new DayFacts.CashEvent(far.date.plusDays(31), brl("-800.00")));
        assertThat(InsightFacts.has(far.run(), "MIN_BALANCE_FORECAST")).isFalse();
    }

    @Test
    @DisplayName("eventos vencidos (data antes de hoje) caem no dia zero; saldo já negativo hoje é avisado")
    void deveTratarVencidosNoDiaZero() {
        Day overdue = new Day();
        overdue.cash = brl("100.00");
        overdue.events.add(new DayFacts.CashEvent(overdue.date.minusDays(2), brl("-300.00")));
        Insight insight = only(overdue.run(), "MIN_BALANCE_FORECAST");
        assertThat(insight.params()).containsEntry("firstNegativeDate", "2026-10-17").containsEntry("daysUntil", "0");
    }

    @Test
    @DisplayName("sem contas: insufficient NO_ACCOUNTS; sem agenda: insufficient NO_SCHEDULE")
    void naoDevePreverSemBase() {
        Day noAccounts = new Day();
        noAccounts.hasCash = false;
        assertThat(InsightFacts.insufficient(noAccounts.run(), "MIN_BALANCE_FORECAST", Reason.NO_ACCOUNTS)).isTrue();
        assertThat(InsightFacts.insufficient(new Day().run(), "MIN_BALANCE_FORECAST", Reason.NO_SCHEDULE)).isTrue();
    }

    // ---------------------------------------------------------------- ATYPICAL_ENTRY

    private static List<Money> history(int n, String amount) {
        List<Money> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(brl(amount));
        }
        return list;
    }

    @Test
    @DisplayName("25 lançamentos na categoria e o de hoje acima do p95, com 2x a mediana e ao menos R$ 50: emite com confidence = 25")
    void deveEmitirLancamentoAtipicoPeloP95() {
        List<Money> h = new ArrayList<>(history(24, "30.00"));
        h.add(brl("40.00"));
        Day day = new Day();
        day.history = Map.of(CAT, h);
        day.entries.add(new DayFacts.TodayEntry("tx-1", CAT, brl("200.00")));

        Insight insight = InsightFacts.only(day.run(), "ATYPICAL_ENTRY");

        assertThat(insight.severity()).isEqualTo(Severity.ATTENTION);
        assertThat(insight.confidence().n()).isEqualTo(25);
        assertThat(insight.params()).containsEntry("amount", "200.00").containsEntry("median", "30.00").containsEntry("categoryName", "Alimentação");
        assertThat(insight.subjectId()).isEqualTo("tx-1");
    }

    @Test
    @DisplayName("5 lançamentos na categoria: não emite (insufficient HISTORY); 8 a 19 usam o z robusto")
    void naoDeveEmitirComPoucasObservacoes() {
        Day few = new Day();
        few.history = Map.of(CAT, history(5, "30.00"));
        few.entries.add(new DayFacts.TodayEntry("tx-1", CAT, brl("900.00")));
        InsightSink sink = few.run();
        assertThat(InsightFacts.has(sink, "ATYPICAL_ENTRY")).isFalse();
        assertThat(InsightFacts.insufficient(sink, "ATYPICAL_ENTRY", Reason.HISTORY)).isTrue();

        Day mid = new Day();
        mid.history = Map.of(CAT, history(10, "30.00"));
        mid.entries.add(new DayFacts.TodayEntry("tx-2", CAT, brl("200.00")));
        Insight insight = InsightFacts.only(mid.run(), "ATYPICAL_ENTRY");
        assertThat(insight.confidence().n()).isEqualTo(10);
        assertThat(insight.confidence().z()).isNotNull();
    }

    @Test
    @DisplayName("acima do p95 mas abaixo de R$ 50 ou de 2x a mediana: não emite")
    void naoDeveEmitirSemMaterialidade() {
        Day day = new Day();
        day.history = Map.of(CAT, history(25, "10.00"));
        day.entries.add(new DayFacts.TodayEntry("tx-1", CAT, brl("40.00")));
        assertThat(InsightFacts.has(day.run(), "ATYPICAL_ENTRY")).isFalse();

        Day notDouble = new Day();
        List<Money> wide = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            wide.add(brl((100 + i) + ".00"));
        }
        notDouble.history = Map.of(CAT, wide);
        notDouble.entries.add(new DayFacts.TodayEntry("tx-1", CAT, brl("150.00")));
        assertThat(InsightFacts.has(notDouble.run(), "ATYPICAL_ENTRY")).isFalse();
    }

    // ---------------------------------------------------------------- CARD_CLOSING_SOON

    @Test
    @DisplayName("fatura que fecha em até 7 dias: INFO; em até 2 dias: ATTENTION; depois de 7 dias: nada")
    void deveAvisarDaFaturaQueFecha() {
        Day day = new Day();
        day.closings.add(new DayFacts.Closing("invoice.a.2026-10-25", "Roxinho", day.date.plusDays(8), brl("100.00")));
        day.closings.add(new DayFacts.Closing("invoice.b.2026-10-22", "Cinza", day.date.plusDays(5), brl("250.00")));
        day.closings.add(new DayFacts.Closing("invoice.c.2026-10-19", "Verde", day.date.plusDays(2), brl("80.00")));

        List<Insight> found = day.run().insights().stream().filter(i -> i.type().equals("CARD_CLOSING_SOON")).toList();

        assertThat(found).hasSize(2);
        assertThat(found).anySatisfy(i -> {
            assertThat(i.severity()).isEqualTo(Severity.INFO);
            assertThat(i.params()).containsEntry("cardName", "Cinza").containsEntry("daysUntil", "5").containsEntry("partial", "250.00");
        });
        assertThat(found).anySatisfy(i -> assertThat(i.severity()).isEqualTo(Severity.ATTENTION));
    }

    @Test
    @DisplayName("determinismo: mesma entrada, mesma saída")
    void deveSerDeterministico() {
        Day day = new Day().before(16, "20.00");
        day.today = brl("90.00");
        day.due.add(new DayFacts.Due(day.date.plusDays(2), brl("100.00"), "invoice.x.2026-10-10"));

        assertThat(day.run().insights()).isEqualTo(day.run().insights());
    }
}
