package com.moneylog.core.insights.app;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.insights.domain.DailyInsights;
import com.moneylog.core.insights.domain.DayFacts;
import com.moneylog.core.insights.domain.Insight;
import com.moneylog.core.insights.domain.InsightIds;
import com.moneylog.core.insights.domain.InsightSink;
import com.moneylog.core.insights.domain.MonthFacts;
import com.moneylog.core.insights.domain.MonthlyInsights;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;

@DisplayName("Propriedades dos insights (T299, PR 01): sem dupla contagem, competência da renda e determinismo")
class InsightPropertiesTest {

    private static final int RUNS = 150;
    private static final UUID RECURRENCE = UUID.fromString("00000000-0000-0000-0000-0000000c0c01");

    private static Money m(long cents) {
        return Money.ofCents(cents, com.moneylog.core.sk.money.CurrencyCode.BRL);
    }

    private static LocalDate randomDate(Random random, int fromOffset, int toOffset, LocalDate base) {
        return base.plusDays(fromOffset + random.nextInt(toOffset - fromOffset + 1));
    }

    private record Scenario(FinanceScenarioFactory factory, LocalDate today) {
    }

    private static Scenario scenario(Random random, boolean incomeCompetence) {
        FinanceScenarioFactory s = umCenarioBase();
        LocalDate today = LocalDate.of(2026, 10, 7 + random.nextInt(22));
        int movements = 3 + random.nextInt(14);
        List<Transaction> expenses = new ArrayList<>();
        for (int i = 0; i < movements; i++) {
            LocalDate date = randomDate(random, -120, 8, today);
            Money amount = m(100 + random.nextInt(900_000));
            switch (random.nextInt(8)) {
                case 0, 1 -> {
                    Transaction tx = AccountTransactions.expense(s.header(date, "Compra " + i), amount, s.nubank, s.alimentacao, PaymentMethod.PIX);
                    s.transactions.add(tx);
                    expenses.add(tx);
                }
                case 2 -> s.transactions.add(AccountTransactions.expense(s.header(date, "Fixo " + i).withOrigin(TransactionOrigin.recurrence(RECURRENCE,
                        date)), amount, s.nubank, s.alimentacao, PaymentMethod.PIX));
                case 3 -> s.transactions.add(com.moneylog.core.transaction.domain.CardTransactions.purchase(s.header(date, "Cartão " + i), amount, s.roxinho,
                        s.alimentacao, 1 + random.nextInt(6), s.chain(), today));
                case 4 -> {
                    boolean shift = random.nextBoolean();
                    s.transactions.add(AccountTransactions.income(s.header(date, "Renda " + i), amount, s.nubank, s.salario, PaymentMethod.PIX,
                            incomeCompetence && shift ? Competence.from(date).plusMonths(1) : null));
                }
                case 5 -> s.transactions.add(AccountTransactions.transfer(s.header(date, "Transf " + i), amount, s.nubank, s.xp));
                case 6 -> s.transactions.add(AccountTransactions.investment(s.header(date, "Invest " + i), amount,
                        com.moneylog.core.transaction.domain.InvestmentDirection.CONTRIBUTION, s.nubank, s.xpInvest));
                default -> {
                    if (!expenses.isEmpty()) {
                        Transaction original = expenses.get(random.nextInt(expenses.size()));
                        long cents = 100 + random.nextInt((int) Math.max(1, original.amount().cents() - 100));
                        if (cents < original.amount().cents() && !date.isBefore(original.date())) {
                            s.transactions.add(AccountTransactions.refundToAccount(s.header(date, "Reembolso " + i), m(cents), s.nubank, s.alimentacao,
                                    RefundOrigin.firstRefundOf(original)));
                        }
                    }
                }
            }
        }
        return new Scenario(s, today);
    }

    private static InsightContext context(Scenario sc) {
        FinancialRecords records = sc.factory().records();
        return new InsightContext(USER_ID, sc.today(), records, List.of(), Map.of(sc.factory().alimentacao.id(), "Alimentação"), List::of);
    }

    @Test
    @DisplayName("as quatro classes particionam o consumo e batem com ConsumptionReport; a renda do contexto é a do relatório (150 cenários)")
    void devePartirOConsumoSemDuplaContagem() {
        Random random = new Random(20261016L);
        for (int run = 0; run < RUNS; run++) {
            Scenario sc = scenario(random, true);
            InsightContext context = context(sc);
            FinancialRecords records = sc.factory().records();
            for (int back = 0; back < 4; back++) {
                Competence month = Competence.from(sc.today()).minusMonths(back);
                InsightContext.Classified c = context.classified(month);
                ConsumptionReport report = ConsumptionReport.of(records, records.ledger(), month);

                assertThat(c.installments().add(c.recurring()).add(c.debt()).add(c.variable())).as("run " + run + " " + month).isEqualTo(c.consumption());
                assertThat(c.consumption()).as("run " + run + " " + month).isEqualTo(report.consumption());
                assertThat(c.income()).as("run " + run + " " + month).isEqualTo(report.income());
            }
        }
    }

    @Test
    @DisplayName("parcelas: a carga de 12 meses a partir do mês é a soma das parcelas de cada competência, e a de hoje é a do mês (150 cenários)")
    void deveFecharAsParcelas() {
        Random random = new Random(7L);
        for (int run = 0; run < RUNS; run++) {
            Scenario sc = scenario(random, false);
            InsightContext context = context(sc);
            Competence month = Competence.from(sc.today());
            MonthFacts facts = context.monthFacts(month);

            for (int k = 0; k < 12; k++) {
                assertThat(facts.installmentLoad().get(k)).as("run " + run + " k=" + k).isEqualTo(context.classified(month.plusMonths(k)).installments());
            }
            assertThat(facts.installments()).isEqualTo(facts.installmentLoad().get(0));
        }
    }

    @Test
    @DisplayName("renda por competência: mover a competência da renda só troca de balde (soma total igual) e nunca muda o saldo em contas (150 cenários)")
    void deveManterOCaixaAoMoverACompetencia() {
        Random random = new Random(99L);
        for (int run = 0; run < RUNS; run++) {
            long seed = random.nextLong();
            Scenario plain = scenario(new Random(seed), false);
            Scenario moved = scenario(new Random(seed), true);
            InsightContext a = context(plain);
            InsightContext b = context(moved);
            Competence now = Competence.from(plain.today());

            assertThat(b.monthFacts(now).cashBalance()).as("run " + run).isEqualTo(a.monthFacts(now).cashBalance());
            Money totalA = Money.zero(com.moneylog.core.sk.money.CurrencyCode.BRL);
            Money totalB = totalA;
            for (int back = 0; back < 16; back++) {
                totalA = totalA.add(a.classified(now.minusMonths(back).plusMonths(3)).income());
                totalB = totalB.add(b.classified(now.minusMonths(back).plusMonths(3)).income());
            }
            assertThat(totalB).as("run " + run + ": mesma renda total, só em outros meses").isEqualTo(totalA);
        }
    }

    @Test
    @DisplayName("nenhum insight sai junto do seu 'dado insuficiente'; ids únicos; orçamento do dia nunca negativo; top 3 ≤ consumo; determinismo (150 cenários)")
    void deveRespeitarOsInvariantes() {
        Random random = new Random(2026L);
        for (int run = 0; run < RUNS; run++) {
            Scenario sc = scenario(random, true);
            InsightContext context = context(sc);
            Competence month = Competence.from(sc.today());
            for (int back = 0; back < 3; back++) {
                MonthFacts facts = context.monthFacts(month.minusMonths(back));
                InsightSink sink = new InsightSink();
                MonthlyInsights.compute(facts, sink);

                Set<String> emitted = new HashSet<>();
                sink.insights().forEach(i -> emitted.add(i.type()));
                for (var d : sink.insufficient()) {
                    assertThat(emitted).as("run " + run + " " + d).doesNotContain(d.type());
                }
                Set<String> ids = new HashSet<>();
                for (Insight i : sink.insights()) {
                    assertThat(ids.add(InsightIds.id(USER_ID, i))).as("id único run " + run + " " + i.type() + "/" + i.subjectId()).isTrue();
                    i.params().values().forEach(v -> assertThat(v).isNotBlank());
                    if (i.type().equals("CONCENTRATION")) {
                        assertThat(new BigDecimal(i.params().get("top3"))).isLessThanOrEqualTo(new BigDecimal(i.params().get("total")));
                    }
                }
                InsightSink again = new InsightSink();
                MonthlyInsights.compute(context(sc).monthFacts(month.minusMonths(back)), again);
                assertThat(again.insights()).as("determinístico run " + run).isEqualTo(sink.insights());
            }
            final int r = run;
            DayFacts day = context.dayFacts(sc.today());
            InsightSink daySink = new InsightSink();
            DailyInsights.compute(day, daySink);
            daySink.insights().stream().filter(i -> i.type().equals("DAILY_BUDGET")).forEach(i -> assertThat(new BigDecimal(i.params().get("amount")))
                    .as("run " + r).isGreaterThanOrEqualTo(BigDecimal.ZERO));
            Set<String> dayEmitted = new HashSet<>();
            daySink.insights().forEach(i -> dayEmitted.add(i.type()));
            daySink.insufficient().forEach(d -> assertThat(dayEmitted).as("run " + r + " " + d).doesNotContain(d.type()));
        }
    }

    @Test
    @DisplayName("orçamento do dia = livre restante / dias (com hoje), e o livre fecha com recebido − gasto − a acontecer − reservado (150 cenários)")
    void deveFecharOOrcamento() {
        Random random = new Random(31L);
        for (int run = 0; run < RUNS; run++) {
            Scenario sc = scenario(random, true);
            DayFacts day = context(sc).dayFacts(sc.today());
            MonthFacts.Budget b = day.budget();

            assertThat(b.free()).isEqualTo(b.received().subtract(b.spentSoFar()).subtract(b.laterKnown()).subtract(b.pendingRecurring())
                    .subtract(b.reserved()));
            InsightSink sink = new InsightSink();
            DailyInsights.compute(day, sink);
            if (b.received().isPositive() && !b.free().isNegative()) {
                Insight budget = InsightFactsOnly.of(sink, "DAILY_BUDGET");
                long daysLeft = sc.today().lengthOfMonth() - sc.today().getDayOfMonth() + 1;
                long expected = b.free().cents() / daysLeft;
                assertThat(Money.of(budget.params().get("amount")).cents()).as("run " + run).isEqualTo(expected);
            }
        }
    }

    private static final class InsightFactsOnly {
        static Insight of(InsightSink sink, String type) {
            return sink.insights().stream().filter(i -> i.type().equals(type)).findFirst().orElseThrow();
        }
    }
}
