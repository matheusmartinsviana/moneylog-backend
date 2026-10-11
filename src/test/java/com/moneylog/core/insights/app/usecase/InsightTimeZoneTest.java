package com.moneylog.core.insights.app.usecase;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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
import com.moneylog.core.insights.app.usecase.InsightUseCases.InsightsView;
import com.moneylog.core.insights.domain.DailyInsights;
import com.moneylog.core.insights.domain.DayFacts;
import com.moneylog.core.insights.domain.InsightSink;
import com.moneylog.core.insights.domain.MonthFacts;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;

@DisplayName("Insights do dia: fuso, virada de mês e horário de verão (T300)")
class InsightTimeZoneTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    private static TimeProvider at(String instant, ZoneId zone) {
        return new ClockTimeProvider(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), zone);
    }

    private static DayFacts day(LocalDate date, String received, String spent) {
        Money zero = Money.zero(CurrencyCode.BRL);
        return new DayFacts(date, new MonthFacts.Budget(Money.of(received), Money.of(spent), zero, zero, zero), List.of(), zero, Money.of("100.00"), true,
                List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of());
    }

    private static String daysLeft(DayFacts f) {
        InsightSink sink = new InsightSink();
        DailyInsights.compute(f, sink);
        return sink.insights().stream().filter(i -> i.type().equals("DAILY_BUDGET")).findFirst().orElseThrow().params().get("daysLeft");
    }

    @Test
    @DisplayName("31/10 às 23:59 em São Paulo é o dia 31 (1 dia); 1/11 às 00:00 é o dia 1 de novembro (30 dias)")
    void deveVirarOMesNoFusoDoUsuario() {
        TimeProvider lastMinute = at("2026-11-01T02:59:00Z", SAO_PAULO);
        TimeProvider midnight = at("2026-11-01T03:00:00Z", SAO_PAULO);

        assertThat(lastMinute.today()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(midnight.today()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(daysLeft(day(lastMinute.today(), "1000.00", "0.00"))).isEqualTo("1");
        assertThat(daysLeft(day(midnight.today(), "1000.00", "0.00"))).isEqualTo("30");
    }

    @Test
    @DisplayName("fim do horário de verão em Nova York: a hora 1h30 que se repete é o mesmo dia 1/11 nas duas vezes")
    void deveManterODiaNaHoraRepetida() {
        LocalDate first = at("2026-11-01T05:30:00Z", ZoneId.of("America/New_York")).today();
        LocalDate second = at("2026-11-01T06:30:00Z", ZoneId.of("America/New_York")).today();

        assertThat(first).isEqualTo(second).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(daysLeft(day(first, "1000.00", "0.00"))).isEqualTo(daysLeft(day(second, "1000.00", "0.00"))).isEqualTo("30");
    }

    @Test
    @DisplayName("início do horário de verão em São Paulo (04/11/2018, meia-noite não existiu): 3/11 às 23:59 e 4/11 à 01:00 são dias seguidos")
    void deveContarOsDiasNaViradaDoHorarioDeVerao() {
        LocalDate before = at("2018-11-04T02:59:59Z", SAO_PAULO).today();
        LocalDate after = at("2018-11-04T03:00:00Z", SAO_PAULO).today();

        assertThat(before).isEqualTo(LocalDate.of(2018, 11, 3));
        assertThat(after).isEqualTo(LocalDate.of(2018, 11, 4));
        assertThat(daysLeft(day(before, "1000.00", "0.00"))).isEqualTo("28");
        assertThat(daysLeft(day(after, "1000.00", "0.00"))).isEqualTo("27");
    }

    @Test
    @DisplayName("fevereiro bissexto (29 dias) e não bissexto (28); mês de 31 dias")
    void deveContarFevereiroEMesesDe31Dias() {
        assertThat(daysLeft(day(LocalDate.of(2028, 2, 1), "1000.00", "0.00"))).isEqualTo("29");
        assertThat(daysLeft(day(LocalDate.of(2027, 2, 1), "1000.00", "0.00"))).isEqualTo("28");
        assertThat(daysLeft(day(LocalDate.of(2028, 2, 29), "1000.00", "0.00"))).isEqualTo("1");
        assertThat(daysLeft(day(LocalDate.of(2026, 12, 1), "1000.00", "0.00"))).isEqualTo("31");
    }

    @Test
    @DisplayName("caso de uso na virada do mês: às 23:59 de 31/10 o insight é de outubro; à 00:00 de 1/11 é de novembro e não herda a renda de outubro")
    void deveVirarOMesNoCasoDeUso() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.income(s.header(LocalDate.of(2026, 10, 2), "Folha"), Money.of("4000.00"), s.nubank, s.salario,
                PaymentMethod.PIX));
        InsightsView october = daily("2026-11-01T02:59:00Z", s);
        InsightsView november = daily("2026-11-01T03:00:00Z", s);

        assertThat(october.items()).anySatisfy(i -> {
            assertThat(i.insight().type()).isEqualTo("DAILY_BUDGET");
            assertThat(i.insight().period().value()).isEqualTo("2026-10-31");
            assertThat(i.insight().params()).containsEntry("daysLeft", "1").containsEntry("free", "4000.00");
        });
        assertThat(november.items()).noneMatch(i -> i.insight().type().equals("DAILY_BUDGET"));
        assertThat(november.insufficient()).anySatisfy(d -> assertThat(d.type()).isEqualTo("DAILY_BUDGET"));
        assertThat(daily("2026-11-01T02:59:00Z", s).items()).isEqualTo(october.items());
    }

    private InsightsView daily(String instant, FinanceScenarioFactory s) {
        FinancialRecordsGateway records = mock(FinancialRecordsGateway.class);
        CommitmentSourcesGateway sources = mock(CommitmentSourcesGateway.class);
        CategoryGateway categories = mock(CategoryGateway.class);
        InsightDismissalGateway dismissals = mock(InsightDismissalGateway.class);
        when(records.load(any())).thenReturn(s.records());
        when(sources.recurrences(any())).thenReturn(List.of());
        when(categories.list(any(), any(), anyBoolean())).thenReturn(List.of());
        when(dismissals.findActive(any())).thenReturn(List.of());
        TimeProvider time = at(instant, SAO_PAULO);
        InsightContextFactory contexts = new InsightContextFactory(records, sources, categories, mock(GoalSnapshotGateway.class), time);
        return new InsightUseCasesImpl.GetDailyInsights(contexts, dismissals, time).handle(new DailyQuery(USER_ID, null, false, 0, 50));
    }
}
