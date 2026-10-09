package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.moneylog.core.finance.domain.CashFlowReport;
import com.moneylog.core.finance.domain.CashPosition;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.IncomeExpenseSeries;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.finance.domain.MonthlySummary;
import com.moneylog.core.finance.domain.NetWorth;
import com.moneylog.core.finance.domain.ReportPeriod;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * ADR-017 e PR 01 (regras 2, 9 e 10): a renda pode pertencer a outro mês; o caixa, o saldo e o consumo não mudam; nenhum centavo some ou
 * duplica ao mover de mês; dashboard, lista e resumo do mesmo mês coincidem. Só dados sintéticos.
 */
@DisplayName("Renda de outro mês: competência da renda × caixa (ADR-017)")
class RendaDeOutroMesTest {

    private static final Competence SET = Competence.of(2026, 9);
    private static final Competence OUT = Competence.of(2026, 10);
    private static final LocalDate HOJE = LocalDate.of(2026, 10, 10);

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static Money m(String value) {
        return Money.of(value);
    }

    /** O salário dividido: adiantamento em 16/09 que é renda de outubro + folha em 02/10. */
    private static FinanceScenarioFactory salarioDividido(boolean comCompetencia) {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.income(s.header(d("2026-09-16"), "Adiantamento"), m("1500.00"), s.nubank, s.salario,
                PaymentMethod.PIX, comCompetencia ? OUT : null));
        s.transactions.add(AccountTransactions.income(s.header(d("2026-10-02"), "Folha"), m("2500.00"), s.nubank, s.salario,
                PaymentMethod.PIX));
        s.transactions.add(AccountTransactions.expense(s.header(d("2026-09-20"), "Mercado"), m("300.00"), s.nubank, s.alimentacao, PaymentMethod.PIX));
        s.transactions.add(AccountTransactions.expense(s.header(d("2026-10-05"), "Feira"), m("120.00"), s.nubank, s.alimentacao, PaymentMethod.PIX));
        return s;
    }

    @Test
    @DisplayName("renda de outubro = adiantamento (16/09) + folha (02/10); setembro não tem renda; dashboard = lista = resumo")
    void deveSomarAsDuasParcelasEmOutubro() {
        FinanceScenarioFactory s = salarioDividido(true);
        FinancialRecords records = s.records();
        Ledger ledger = records.ledger();

        ConsumptionReport out = ConsumptionReport.of(records, ledger, OUT);
        ConsumptionReport set = ConsumptionReport.of(records, ledger, SET);
        MonthlySummary resumo = MonthlySummary.of(records, ledger, OUT, HOJE);
        DashboardSummary dashboard = DashboardSummary.of(records, List.of(), HOJE, HOJE);
        EntryReport lista = EntryReport.of(records, ledger, EntryLens.INCOME, OUT);

        assertThat(out.income()).isEqualTo(m("4000.00"));
        assertThat(set.income()).isEqualTo(m("0.00"));
        assertThat(resumo.income()).isEqualTo(m("4000.00"));
        assertThat(dashboard.month().income()).isEqualTo(m("4000.00"));
        assertThat(lista.total()).isEqualTo(m("4000.00"));
        assertThat(resumo.net()).isEqualTo(m("3880.00")).isEqualTo(dashboard.month().net());
        assertThat(resumo.comparison().previousTotals().income()).isEqualTo(m("0.00"));
        assertThat(resumo.comparison().income().amount()).isEqualTo(m("4000.00"));
        assertThat(resumo.savingsRate()).isEqualByComparingTo("0.9700");
        assertThat(EntryReport.of(records, ledger, EntryLens.INCOME, SET).lines()).isEmpty();
        // as linhas guardam a data real e a competência
        assertThat(lista.lines()).extracting(EntryReport.Line::date).containsExactlyInAnyOrder(d("2026-09-16"), d("2026-10-02"));
        assertThat(lista.lines()).extracting(EntryReport.Line::competence).containsOnly(OUT);
    }

    @Test
    @DisplayName("o caixa, o saldo, o patrimônio e o consumo são idênticos com e sem a competência de renda")
    void deveManterCaixaSaldoEConsumo() {
        FinancialRecords com = salarioDividido(true).records();
        FinancialRecords sem = salarioDividido(false).records();

        for (String dia : List.of("2026-09-15", "2026-09-16", "2026-09-30", "2026-10-02", "2026-10-10")) {
            assertThat(CashPosition.at(com, com.ledger(), d(dia))).isEqualTo(CashPosition.at(sem, sem.ledger(), d(dia)));
            assertThat(NetWorth.at(com.ledger(), d(dia))).isEqualTo(NetWorth.at(sem.ledger(), d(dia)));
        }
        // 16/09: o dinheiro já entrou na conta (caixa de setembro), mesmo sendo renda de outubro
        assertThat(CashFlowReport.between(com, com.ledger(), d("2026-09-01"), d("2026-09-30")).in()).isEqualTo(m("1500.00"));
        assertThat(CashFlowReport.between(com, com.ledger(), d("2026-10-01"), d("2026-10-31")).in()).isEqualTo(m("2500.00"));
        for (Competence mes : List.of(SET, OUT)) {
            assertThat(ConsumptionReport.of(com, com.ledger(), mes).consumption()).isEqualTo(ConsumptionReport.of(sem, sem.ledger(), mes).consumption());
            assertThat(CashFlowReport.forCompetence(com, com.ledger(), mes)).isEqualTo(CashFlowReport.forCompetence(sem, sem.ledger(), mes));
        }
        // sem o campo, a renda de setembro é 1.500 e a de outubro 2.500 (comportamento de sempre)
        assertThat(ConsumptionReport.of(sem, sem.ledger(), SET).income()).isEqualTo(m("1500.00"));
        assertThat(ConsumptionReport.of(sem, sem.ledger(), OUT).income()).isEqualTo(m("2500.00"));
    }

    @Test
    @DisplayName("relatório entradas e saídas: MONTH usa a competência (4.000 em outubro) e DAY usa a data real (1.500 em 16/09)")
    void deveSepararMesDeDia() {
        FinanceScenarioFactory s = salarioDividido(true);
        FinancialRecords records = s.records();
        Ledger ledger = records.ledger();

        IncomeExpenseSeries meses = IncomeExpenseSeries.of(ledger, ReportPeriod.M12, HOJE);
        IncomeExpenseSeries dias = IncomeExpenseSeries.of(ledger, ReportPeriod.D30, HOJE);

        assertThat(meses.basis()).isEqualTo(IncomeExpenseSeries.Basis.COMPETENCE);
        assertThat(meses.points()).filteredOn(p -> p.periodStart().equals(d("2026-10-01"))).singleElement()
                .satisfies(p -> assertThat(p.income()).isEqualTo(m("4000.00")));
        assertThat(meses.points()).filteredOn(p -> p.periodStart().equals(d("2026-09-01"))).singleElement()
                .satisfies(p -> assertThat(p.income()).isEqualTo(m("0.00")));
        assertThat(dias.basis()).isEqualTo(IncomeExpenseSeries.Basis.DATE);
        assertThat(dias.points()).filteredOn(p -> p.periodStart().equals(d("2026-09-16"))).singleElement()
                .satisfies(p -> assertThat(p.income()).isEqualTo(m("1500.00")));
        assertThat(dias.points()).filteredOn(p -> p.periodStart().equals(d("2026-10-02"))).singleElement()
                .satisfies(p -> assertThat(p.income()).isEqualTo(m("2500.00")));
        // a renda de setembro/outubro somada é a mesma nas duas bases (nada some, nada duplica)
        assertThat(dias.totals().income()).isEqualTo(m("4000.00"));
        assertThat(meses.totals().income()).isEqualTo(m("4000.00"));
    }

    @Test
    @DisplayName("renda com competência futura (adiantamento de dezembro atribuído a janeiro) não entra em nenhum mês anterior nem duplica")
    void deveManterRendaFuturaFora() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.income(s.header(d("2026-12-20"), "13o"), m("900.00"), s.nubank, s.salario, PaymentMethod.PIX,
                Competence.of(2027, 1)));
        FinancialRecords records = s.records();

        assertThat(ConsumptionReport.of(records, records.ledger(), Competence.of(2026, 12)).income()).isEqualTo(m("0.00"));
        assertThat(ConsumptionReport.of(records, records.ledger(), Competence.of(2027, 1)).income()).isEqualTo(m("900.00"));
        assertThat(EntryReport.of(records, records.ledger(), EntryLens.INCOME, Competence.of(2027, 1)).total()).isEqualTo(m("900.00"));
    }

    // ------------------------------------------------------------------ propriedade

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 40);
    }

    private record IncomeSpec(LocalDate date, long cents, Competence moved) {
    }

    private record ExpenseSpec(LocalDate date, long cents) {
    }

    private static FinanceScenarioFactory build(List<IncomeSpec> incomes, List<ExpenseSpec> expenses, boolean applyMoves) {
        FinanceScenarioFactory s = umCenarioBase();
        for (IncomeSpec i : incomes) {
            s.transactions.add(AccountTransactions.income(s.header(i.date(), "Renda"), Money.ofCents(i.cents(), CurrencyCode.BRL), s.nubank,
                    s.salario, PaymentMethod.PIX, applyMoves ? i.moved() : null));
        }
        for (ExpenseSpec e : expenses) {
            s.transactions.add(AccountTransactions.expense(s.header(e.date(), "Gasto"), Money.ofCents(e.cents(), CurrencyCode.BRL), s.nubank,
                    s.alimentacao, PaymentMethod.PIX));
        }
        return s;
    }

    @ParameterizedTest(name = "semente {0}")
    @MethodSource("seeds")
    @DisplayName("propriedade: mover a competência de N entradas muda só os meses afetados, pelo valor exato; caixa, saldo e consumo ficam intactos")
    void deveConservarCadaCentavoAoMoverDeMes(long seed) {
        Random r = new Random(seed);
        LocalDate hoje = LocalDate.of(2026, 12, 15);
        List<IncomeSpec> incomes = new ArrayList<>();
        int n = 3 + r.nextInt(25);
        for (int i = 0; i < n; i++) {
            LocalDate date = LocalDate.of(2026, 1, 1).plusDays(r.nextInt(350));
            Competence moved = r.nextInt(100) < 55 ? Competence.from(date).plusMonths(r.nextInt(5) - 2) : null;
            incomes.add(new IncomeSpec(date, 1 + r.nextInt(900_000), moved));
        }
        List<ExpenseSpec> expenses = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            expenses.add(new ExpenseSpec(LocalDate.of(2026, 1, 1).plusDays(r.nextInt(350)), 1 + r.nextInt(60_000)));
        }
        FinancialRecords before = build(incomes, expenses, false).records();
        FinancialRecords after = build(incomes, expenses, true).records();
        Ledger lb = before.ledger();
        Ledger la = after.ledger();

        // o delta esperado por mês: + valor no mês novo, − valor no mês antigo
        Map<Competence, Long> delta = new TreeMap<>();
        long totalCents = 0;
        for (IncomeSpec i : incomes) {
            totalCents += i.cents();
            if (i.moved() != null) {
                delta.merge(i.moved(), i.cents(), Long::sum);
                delta.merge(Competence.from(i.date()), -i.cents(), Long::sum);
            }
        }

        long sumBefore = 0;
        long sumAfter = 0;
        for (Competence month = Competence.of(2025, 6); !month.isAfter(Competence.of(2027, 6)); month = month.plusMonths(1)) {
            ConsumptionReport rb = ConsumptionReport.of(before, lb, month);
            ConsumptionReport ra = ConsumptionReport.of(after, la, month);
            long expected = rb.income().cents() + delta.getOrDefault(month, 0L);
            assertThat(ra.income().cents()).as("semente %d, renda de %s", seed, month).isEqualTo(expected);
            sumBefore += rb.income().cents();
            sumAfter += ra.income().cents();
            // consumo e caixa do mês: intactos
            assertThat(ra.consumption()).isEqualTo(rb.consumption());
            assertThat(CashFlowReport.forCompetence(after, la, month)).isEqualTo(CashFlowReport.forCompetence(before, lb, month));
            // dashboard = lista = resumo do MESMO mês
            MonthlySummary resumo = MonthlySummary.of(after, la, month, hoje);
            EntryReport lista = EntryReport.of(after, la, EntryLens.INCOME, month);
            LocalDate asOf = month.lastDay().isAfter(hoje) ? hoje : month.lastDay();
            if (Competence.from(asOf).equals(month)) {
                assertThat(DashboardSummary.of(after, List.of(), asOf, hoje).month().income()).as("dashboard %s", month).isEqualTo(ra.income());
            }
            assertThat(resumo.income()).isEqualTo(ra.income());
            assertThat(lista.total()).isEqualTo(ra.income());
            assertThat(resumo.net()).isEqualTo(ra.income().subtract(ra.consumption()));
        }
        // soma de todos os meses = soma de todas as entradas (nenhum centavo some ou duplica)
        assertThat(sumBefore).isEqualTo(totalCents);
        assertThat(sumAfter).isEqualTo(totalCents);

        // caixa e saldo por data: intactos em todo dia relevante
        for (LocalDate day = LocalDate.of(2025, 12, 31); !day.isAfter(LocalDate.of(2027, 1, 5)); day = day.plusDays(9)) {
            assertThat(CashPosition.at(after, la, day)).isEqualTo(CashPosition.at(before, lb, day));
            assertThat(NetWorth.at(la, day)).isEqualTo(NetWorth.at(lb, day));
        }

        // séries: MONTH = resumo; DAY/WEEK = caixa (iguais às de antes, que só dependem da data real)
        IncomeExpenseSeries meses = IncomeExpenseSeries.of(la, ReportPeriod.M12, hoje);
        for (IncomeExpenseSeries.Point p : meses.points()) {
            assertThat(p.income()).as("série MONTH %s", p.periodStart()).isEqualTo(ConsumptionReport.of(after, la, Competence.from(p.periodStart())).income());
            assertThat(p.expenses()).isEqualTo(ConsumptionReport.of(after, la, Competence.from(p.periodStart())).consumption());
        }
        for (ReportPeriod period : List.of(ReportPeriod.D7, ReportPeriod.D30, ReportPeriod.M3)) {
            assertThat(IncomeExpenseSeries.of(la, period, hoje).points()).isEqualTo(IncomeExpenseSeries.of(lb, period, hoje).points());
        }
        Map<LocalDate, Long> porDia = new HashMap<>();
        incomes.forEach(i -> porDia.merge(i.date(), i.cents(), Long::sum));
        IncomeExpenseSeries dias = IncomeExpenseSeries.of(la, ReportPeriod.D30, hoje);
        dias.points().forEach(p -> assertThat(p.income().cents()).as("série DAY %s", p.periodStart())
                .isEqualTo(porDia.getOrDefault(p.periodStart(), 0L)));
    }
}
