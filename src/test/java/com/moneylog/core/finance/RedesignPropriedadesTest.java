package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.moneylog.core.finance.domain.CashPosition;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.IncomeExpenseSeries;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.finance.domain.MonthlySummary;
import com.moneylog.core.finance.domain.ReportPeriod;
import com.moneylog.core.finance.domain.SpendingByCategory;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

/**
 * T190 (propriedades, regra 10 do PR 01): para sequências aleatórias de operações válidas (as da {@link PropriedadesRazaoAleatorioTest} mais
 * receita, despesa agendada, reembolso de conta e empréstimo), em várias datas de avaliação:
 * <ol>
 *   <li>cada ponto da série {@code M12} é igual ao {@code ConsumptionReport}, ao total do {@code EntryReport}, ao {@code MonthlySummary} e ao
 *       {@code DashboardSummary.month} da mesma competência (mesmas calculadoras do core, sem dupla contagem);</li>
 *   <li>cada ponto de {@code D7}, {@code D30} e {@code M3} é igual à soma dos postings dos livros {@code INCOME}/{@code EXPENSE} no intervalo
 *       (oráculo independente), nada passa de hoje e {@code totals} é a soma dos pontos;</li>
 *   <li>a soma dos 30 dias de um mês de 30 dias fechado é o ponto mensal e o {@code month.consumption} do dashboard;</li>
 *   <li>fatura paga, transferência, aplicação, empréstimo e quitação nunca tocam os livros da série;</li>
 *   <li>o consumo por categoria fecha com {@code entries.totals.total}, {@code base} = Σ positivas e o {@code share} respeita a tolerância de
 *       arredondamento;</li>
 *   <li>{@code inAccountsChange.previous} é o {@code inAccounts} de um dashboard pedido no último dia do mês anterior e o delta da taxa de
 *       poupança é a diferença das taxas já arredondadas.</li>
 * </ol>
 */
@DisplayName("Propriedades do redesign: série, consumo por categoria e variações × as calculadoras do core (T190, PR 01 regra 10)")
class RedesignPropriedadesTest {

    private static final int STEPS = 120;
    private static final LocalDate START = LocalDate.of(2026, 10, 1);

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 30);
    }

    @ParameterizedTest(name = "semente {0}")
    @MethodSource("seeds")
    @DisplayName("a série, o consumo por categoria e as variações batem com dashboard, entries e resumo mensal")
    void deveBaterComAsCalculadoras(long seed) {
        Random random = new Random(seed);
        FinanceScenarioFactory s = umCenarioBase();
        LocalDate today = START;
        Set<UUID> refunded = new HashSet<>();
        for (int step = 0; step < STEPS; step++) {
            today = today.plusDays(random.nextInt(6));
            Instant now = today.atTime(15, 0).toInstant(ZoneOffset.UTC);
            PropriedadesRazaoAleatorioTest.apply(s, random, today, now);
            extras(s, random, today, refunded);
        }
        FinancialRecords records = s.records();
        Ledger ledger = records.ledger();
        IncomeExpenseSeries whole = IncomeExpenseSeries.of(ledger, ReportPeriod.M12, today);
        assertThat(whole.totals().income().isPositive()).as("o cenário gerou receita").isTrue();
        assertThat(whole.totals().expenses().isPositive()).as("o cenário gerou consumo").isTrue();
        List<LocalDate> evaluations = List.of(today, LocalDate.of(2026, 11, 30), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31));
        for (LocalDate evaluation : evaluations) {
            String ctx = "semente " + seed + ", avaliação " + evaluation;
            checkSeries(records, ledger, evaluation, ctx);
            checkSpending(records, ledger, evaluation, random, ctx);
            checkChanges(records, ledger, evaluation, ctx);
        }
        checkClosedMonthByDays(records, ledger, seed);
        checkOutOfBooks(s, ledger, seed);
    }

    // ------------------------------------------------------------------ geração extra

    private static void extras(FinanceScenarioFactory s, Random r, LocalDate today, Set<UUID> refunded) {
        int kind = r.nextInt(100);
        try {
            if (kind < 14) {
                s.transactions.add(AccountTransactions.income(s.header(today.minusDays(r.nextInt(20)), "Receita"), cents(r, 1, 400_000),
                        s.nubank, s.salario, PaymentMethod.PIX));
            } else if (kind < 22) {
                s.transactions.add(AccountTransactions.expense(s.header(today.plusDays(1 + r.nextInt(40)), "Agendada"), cents(r, 1, 30_000),
                        s.nubank, s.alimentacao, PaymentMethod.PIX));
            } else if (kind < 34) {
                List<Transaction> candidates = s.transactions.stream()
                        .filter(t -> t.isActive() && t.type() == TransactionType.EXPENSE && !refunded.contains(t.id())).toList();
                if (!candidates.isEmpty()) {
                    Transaction original = candidates.get(r.nextInt(candidates.size()));
                    LocalDate date = today.isBefore(original.date()) ? original.date() : today.minusDays(r.nextInt(3));
                    Money amount = Money.ofCents(1 + r.nextLong(original.amount().cents()), CurrencyCode.BRL);
                    s.transactions.add(AccountTransactions.refundToAccount(s.header(date.isBefore(original.date()) ? original.date() : date,
                            "Reembolso"), amount, s.nubank, s.alimentacao, RefundOrigin.firstRefundOf(original)));
                    refunded.add(original.id());
                }
            } else if (kind < 38 && s.debts.isEmpty()) {
                s.caso6Emprestimo();
            } else if (kind < 42 && s.debts.size() == 1 && s.transactions.stream().noneMatch(t -> t.type() == TransactionType.MONEY_RECEIVED)) {
                s.caso7Devolucao();
            }
        } catch (RuntimeException e) {
            // operação recusada pelo domínio (data, limite...): a sequência segue
        }
    }

    private static Money cents(Random r, long min, long max) {
        return Money.ofCents(min + r.nextLong(max - min), CurrencyCode.BRL);
    }

    // ------------------------------------------------------------------ série

    private static void checkSeries(FinancialRecords records, Ledger ledger, LocalDate today, String ctx) {
        Money zero = Money.zero(CurrencyCode.BRL);
        for (ReportPeriod period : ReportPeriod.values()) {
            IncomeExpenseSeries series = IncomeExpenseSeries.of(ledger, period, today);
            Money sumIncome = zero;
            Money sumExpenses = zero;
            for (IncomeExpenseSeries.Point point : series.points()) {
                LocalDate end = period == ReportPeriod.M12 ? point.periodEnd() : min(point.periodEnd(), today);
                Money income = oracle(ledger, BookType.INCOME, point.periodStart(), end).negate();
                Money expenses = oracle(ledger, BookType.EXPENSE, point.periodStart(), end);
                assertThat(point.income()).as(ctx + ": " + period + " receita " + point.periodStart()).isEqualTo(income);
                assertThat(point.expenses()).as(ctx + ": " + period + " consumo " + point.periodStart()).isEqualTo(expenses);
                assertThat(point.net()).as(ctx + ": net").isEqualTo(income.subtract(expenses));
                assertThat(period == ReportPeriod.M12 || !point.periodEnd().isAfter(today)).as(ctx + ": nada depois de hoje").isTrue();
                sumIncome = sumIncome.add(point.income());
                sumExpenses = sumExpenses.add(point.expenses());
            }
            assertThat(series.totals().income()).as(ctx + ": totals.income " + period).isEqualTo(sumIncome);
            assertThat(series.totals().expenses()).as(ctx + ": totals.expenses " + period).isEqualTo(sumExpenses);
            assertThat(series.totals().net()).isEqualTo(sumIncome.subtract(sumExpenses));
            assertThat(series.points().get(0).periodStart()).isEqualTo(series.from());
            assertThat(series.points().get(series.points().size() - 1).periodEnd()).isEqualTo(series.to());
            if (period == ReportPeriod.M12) {
                checkMonthlyAgainstCalculators(records, ledger, series, today, ctx);
            }
        }
    }

    private static void checkMonthlyAgainstCalculators(FinancialRecords records, Ledger ledger, IncomeExpenseSeries series, LocalDate today,
                                                       String ctx) {
        for (IncomeExpenseSeries.Point point : series.points()) {
            Competence month = Competence.from(point.periodStart());
            ConsumptionReport report = ConsumptionReport.of(records, ledger, month);
            assertThat(point.income()).as(ctx + ": ConsumptionReport.income " + month).isEqualTo(report.income());
            assertThat(point.expenses()).as(ctx + ": ConsumptionReport.consumption " + month).isEqualTo(report.consumption());
            assertThat(point.income()).as(ctx + ": EntryReport INCOME " + month)
                    .isEqualTo(EntryReport.of(records, ledger, EntryLens.INCOME, month).total());
            assertThat(point.expenses()).as(ctx + ": EntryReport CONSUMPTION " + month)
                    .isEqualTo(EntryReport.of(records, ledger, EntryLens.CONSUMPTION, month).total());
            MonthlySummary summary = MonthlySummary.of(records, ledger, month, today);
            assertThat(point.income()).as(ctx + ": MonthlySummary.income " + month).isEqualTo(summary.income());
            assertThat(point.expenses()).as(ctx + ": MonthlySummary.expenses " + month).isEqualTo(summary.expenses());
            assertThat(point.net()).as(ctx + ": MonthlySummary.net " + month).isEqualTo(summary.net());
            DashboardSummary dashboard = DashboardSummary.of(records, List.of(), month.lastDay(), today);
            assertThat(point.income()).as(ctx + ": dashboard month.income " + month).isEqualTo(dashboard.month().income());
            assertThat(point.expenses()).as(ctx + ": dashboard month.consumption " + month).isEqualTo(dashboard.month().consumption());
        }
    }

    private static Money oracle(Ledger ledger, BookType book, LocalDate from, LocalDate to) {
        return ledger.sum(e -> e.posting().bookType() == book && !e.posting().effectiveDate().isBefore(from)
                && !e.posting().effectiveDate().isAfter(to));
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    /** D30 em 30/11 cobre exatamente novembro: a soma dos dias é o ponto mensal e o consumo do dashboard daquele mês. */
    private static void checkClosedMonthByDays(FinancialRecords records, Ledger ledger, long seed) {
        LocalDate lastDay = LocalDate.of(2026, 11, 30);
        IncomeExpenseSeries days = IncomeExpenseSeries.of(ledger, ReportPeriod.D30, lastDay);
        IncomeExpenseSeries months = IncomeExpenseSeries.of(ledger, ReportPeriod.M12, lastDay);
        IncomeExpenseSeries.Point november = months.points().get(11);
        assertThat(days.points()).hasSize(30);
        assertThat(days.points().get(0).periodStart()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(days.totals().income()).as("semente " + seed + ": Σ dias = ponto MONTH (receita)").isEqualTo(november.income());
        assertThat(days.totals().expenses()).as("semente " + seed + ": Σ dias = ponto MONTH (consumo)").isEqualTo(november.expenses());
        DashboardSummary dashboard = DashboardSummary.of(records, List.of(), lastDay, lastDay);
        assertThat(days.totals().expenses()).isEqualTo(dashboard.month().consumption());
        assertThat(days.totals().income()).isEqualTo(dashboard.month().income());
    }

    /** Nada que não seja receita ou consumo toca os livros da série (zero dupla contagem). */
    private static void checkOutOfBooks(FinanceScenarioFactory s, Ledger ledger, long seed) {
        Set<TransactionType> outside = Set.of(TransactionType.INVOICE_PAYMENT, TransactionType.TRANSFER, TransactionType.INVESTMENT,
                TransactionType.LOAN_GIVEN, TransactionType.MONEY_RECEIVED, TransactionType.DEBT_PAYMENT, TransactionType.ADJUSTMENT);
        for (LedgerEntry e : ledger.entries()) {
            if (e.source().type() != PostingSourceType.TRANSACTION) {
                continue;
            }
            Transaction t = s.transactions.stream().filter(x -> x.id().equals(e.source().transactionId())).findFirst().orElseThrow();
            if (outside.contains(t.type())) {
                assertThat(e.posting().bookType()).as("semente " + seed + ": " + t.type() + " fora da série").isNotIn(BookType.INCOME, BookType.EXPENSE);
            }
        }
    }

    // ------------------------------------------------------------------ consumo por categoria

    private static void checkSpending(FinancialRecords records, Ledger ledger, LocalDate today, Random random, String ctx) {
        for (Competence month = Competence.from(today).minusMonths(11); !month.isAfter(Competence.from(today)); month = month.plusMonths(1)) {
            EntryReport entries = EntryReport.of(records, ledger, EntryLens.CONSUMPTION, month);
            int limit = 1 + random.nextInt(10);
            SpendingByCategory spending = SpendingByCategory.of(entries, limit, id -> id.toString());
            String where = ctx + ", " + month + ", limit " + limit;
            assertThat(spending.total()).as(where + ": total = entries.totals.total").isEqualTo(entries.total());
            assertThat(spending.total()).as(where + ": total = month.consumption")
                    .isEqualTo(ConsumptionReport.of(records, ledger, month).consumption());
            Money positives = entries.byCategory().values().stream().filter(Money::isPositive).reduce(Money.zero(CurrencyCode.BRL), Money::add);
            assertThat(spending.base()).as(where + ": base = Σ positivas").isEqualTo(positives);
            Money shown = spending.items().stream().map(SpendingByCategory.Item::amount).reduce(Money.zero(CurrencyCode.BRL), Money::add);
            if (spending.other() != null) {
                shown = shown.add(spending.other().amount());
            }
            assertThat(shown).as(where + ": itens + other = base (nada contado duas vezes)").isEqualTo(spending.base());
            assertThat(spending.items().size()).isLessThanOrEqualTo(limit);
            List<Money> amounts = new ArrayList<>(spending.items().stream().map(SpendingByCategory.Item::amount).toList());
            assertThat(amounts).isSortedAccordingTo((a, b) -> b.compareTo(a));
            assertThat(spending.items()).allSatisfy(i -> assertThat(i.amount().isPositive()).isTrue());
            if (spending.base().isPositive()) {
                BigDecimal sum = spending.items().stream().map(SpendingByCategory.Item::share).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (spending.other() != null) {
                    sum = sum.add(spending.other().share());
                }
                BigDecimal tolerance = new BigDecimal("0.00005").multiply(BigDecimal.valueOf(spending.items().size() + 1L));
                assertThat(sum.subtract(BigDecimal.ONE).abs()).as(where + ": Σ share ≈ 1 dentro de (itens+1)×0,00005")
                        .isLessThanOrEqualTo(tolerance);
                assertThat(sum).as(where + ": nunca passa de 100%").isLessThanOrEqualTo(BigDecimal.ONE.add(tolerance));
                for (SpendingByCategory.Item item : spending.items()) {
                    assertThat(item.share().scale()).isEqualTo(4);
                    assertThat(item.share()).isEqualByComparingTo(BigDecimal.valueOf(item.amount().cents())
                            .divide(BigDecimal.valueOf(spending.base().cents()), 4, java.math.RoundingMode.HALF_UP));
                }
            } else {
                assertThat(spending.items()).isEmpty();
                assertThat(spending.other()).isNull();
            }
        }
    }

    // ------------------------------------------------------------------ variações

    private static void checkChanges(FinancialRecords records, Ledger ledger, LocalDate today, String ctx) {
        DashboardSummary dashboard = DashboardSummary.of(records, List.of(), today, today);
        DashboardSummary.InAccountsChange change = dashboard.balances().inAccountsChange();
        assertThat(change).as(ctx + ": há contas e não é projeção").isNotNull();
        DashboardSummary atSince = DashboardSummary.of(records, List.of(), change.since(), today);
        assertThat(change.since()).isEqualTo(Competence.from(today).minusMonths(1).lastDay());
        assertThat(change.previous()).as(ctx + ": previous = inAccounts de um dashboard em since").isEqualTo(atSince.balances().inAccounts());
        assertThat(change.previous()).isEqualTo(CashPosition.at(records, ledger, change.since()).inAccounts());
        assertThat(change.amount()).as(ctx + ": amount").isEqualTo(dashboard.balances().inAccounts().subtract(change.previous()));
        if (change.previous().isZero()) {
            assertThat(change.percent()).isNull();
        } else {
            assertThat(change.percent()).isEqualByComparingTo(BigDecimal.valueOf(change.amount().cents())
                    .divide(BigDecimal.valueOf(Math.abs(change.previous().cents())), 4, java.math.RoundingMode.HALF_UP));
        }
        assertThat(DashboardSummary.of(records, List.of(), today.plusDays(1), today.minusDays(1)).balances().inAccountsChange())
                .as(ctx + ": projeção omite").isNull();

        MonthlySummary summary = MonthlySummary.of(records, ledger, Competence.from(today), today);
        BigDecimal previous = summary.comparison().previousTotals().savingsRate();
        if (summary.savingsRate() == null || previous == null) {
            assertThat(summary.comparison().savingsRateDelta()).isNull();
        } else {
            assertThat(summary.comparison().savingsRateDelta()).as(ctx + ": delta = diferença das taxas arredondadas")
                    .isEqualByComparingTo(summary.savingsRate().subtract(previous));
        }
        assertThat(previous == null).as(ctx + ": taxa do mês anterior só é nula sem receita")
                .isEqualTo(summary.comparison().previousTotals().income().isZero());
    }
}
