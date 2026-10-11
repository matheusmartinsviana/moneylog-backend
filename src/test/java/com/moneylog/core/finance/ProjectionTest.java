package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.goal.util.GoalTestFactory.GOAL_ID;
import static com.moneylog.core.goal.util.GoalTestFactory.NOW;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.compraNoCalendario;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.domain.Commitment;
import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Horizon;
import com.moneylog.core.finance.domain.Projection;
import com.moneylog.core.finance.domain.SpendingTrend;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.goal.domain.ReserveAllocation;
import com.moneylog.core.goal.util.GoalTestFactory;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionType;

@DisplayName("Projeção determinística (T96, S-152, S-155): sempre projeção, cada compromisso uma vez só")
class ProjectionTest {

    private static final LocalDate TODAY = BASE_DATE;

    private final FinanceScenarioFactory s = cenario();

    private static FinanceScenarioFactory cenario() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        s.compraAVista1083();
        return s;
    }

    private List<RecurringTransaction> recorrencias(LocalDate internetStart) {
        return List.of(
                RecurringTransaction.ofAccount(UUID.fromString("00000000-0000-0000-0000-00000000f001"), USER_ID, TransactionType.EXPENSE,
                        Money.of("119.00"), "Internet", s.alimentacao.id(), s.nubank.id(), PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, internetStart)),
                RecurringTransaction.ofAccount(UUID.fromString("00000000-0000-0000-0000-00000000f002"), USER_ID, TransactionType.INCOME,
                        Money.of("3000.00"), "Salário", s.salario.id(), s.nubank.id(), PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, LocalDate.of(2026, 11, 5))));
    }

    private Projection projection(Horizon horizon, boolean trend) {
        return Projection.of(s.records(), recorrencias(LocalDate.of(2026, 11, 10)), horizon, TODAY, trend);
    }

    private static void ponto(Projection.Point p, String competence, String inflows, String outflows, String balance) {
        assertThat(p.competence()).isEqualTo(Competence.parse(competence));
        assertThat(p.inflows()).as("%s entradas", competence).isEqualTo(Money.of(inflows));
        assertThat(p.outflows()).as("%s saídas", competence).isEqualTo(Money.of(outflows));
        assertThat(p.balance()).as("%s saldo", competence).isEqualTo(Money.of(balance));
    }

    @Test
    @DisplayName("CA1: M6 — um ponto por mês, nov traz a fatura de 1.283 e a Internet de 10/11, dez traz a parcela 2/6 (200) uma vez só; saldo acumulado")
    void deveMontarASerieMensal() {
        Projection p = projection(Horizon.M6, false);

        assertThat(p.series()).hasSize(7);
        ponto(p.series().get(0), "2026-10", "0.00", "0.00", "20000.00");
        ponto(p.series().get(1), "2026-11", "3000.00", "1402.00", "21598.00");
        ponto(p.series().get(2), "2026-12", "3000.00", "319.00", "24279.00");
        ponto(p.series().get(3), "2027-01", "3000.00", "319.00", "26960.00");
        ponto(p.series().get(4), "2027-02", "3000.00", "319.00", "29641.00");
        ponto(p.series().get(5), "2027-03", "3000.00", "319.00", "32322.00");
        ponto(p.series().get(6), "2027-04", "3000.00", "319.00", "35003.00");
        assertThat(p.startingBalance()).isEqualTo(Money.of("20000.00"));
        assertThat(p.series().get(1).periodEnd()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(p.series().getLast().periodEnd()).as("o último ponto termina no fim do horizonte").isEqualTo(LocalDate.of(2027, 4, 10));
    }

    @Test
    @DisplayName("CA2: isProjection é sempre true e as premissas são chaves traduzíveis (sem texto fixo)")
    void deveRotularComoProjecaoEListarPremissas() {
        for (Horizon horizon : Projection.HORIZONS) {
            Projection p = projection(horizon, false);

            assertThat(p.isProjection()).isTrue();
            assertThat(p.horizon()).isEqualTo(horizon);
            assertThat(p.asOf()).isEqualTo(TODAY);
            assertThat(p.assumptions()).containsExactly("ACTIVE_RECURRENCES", "REGISTERED_INVOICES_AND_INSTALLMENTS", "DEBTS_WITH_EXPECTED_DATE",
                    "NO_NEW_SPENDING_TREND");
            assertThat(p.trend()).isNull();
        }
    }

    @Test
    @DisplayName("CA3: a reserva da meta não muda a série (não é saída); o campo goals informa o reservado")
    void naoDeveMudarASerieComMeta() {
        Projection sem = projection(Horizon.M12, false);
        Goal goal = GoalTestFactory.umaMetaDeEmergencia();
        goal.reserve(UUID.randomUUID(), ReserveAllocation.of(s.nubank, Money.of("8500.00")), TODAY, null, NOW);
        FinancialRecords base = s.records();
        FinancialRecords comMeta = new FinancialRecords(base.accounts(), base.cards(), base.transactions(), base.debts(), List.of(goal));

        Projection com = Projection.of(comMeta, recorrencias(LocalDate.of(2026, 11, 10)), Horizon.M12, TODAY, false);

        assertThat(com.series()).isEqualTo(sem.series());
        assertThat(com.startingBalance()).isEqualTo(sem.startingBalance());
        assertThat(sem.goals().reserved()).isEqualTo(Money.of("0.00"));
        assertThat(sem.goals().items()).isEmpty();
        assertThat(com.goals().reserved()).isEqualTo(Money.of("8500.00"));
        assertThat(com.goals().items()).singleElement().satisfies(item -> {
            assertThat(item.goalId()).isEqualTo(GOAL_ID);
            assertThat(item.current()).isEqualTo(Money.of("8500.00"));
            assertThat(item.target()).isEqualTo(Money.of("20000.00"));
        });
        goal.archive();
        assertThat(Projection.of(new FinancialRecords(base.accounts(), base.cards(), base.transactions(), base.debts(), List.of(goal)),
                recorrencias(LocalDate.of(2026, 11, 10)), Horizon.M12, TODAY, false).goals().items()).isEmpty();
    }

    @Test
    @DisplayName("CA6: o último ponto de cada horizonte é igual ao projected do dashboard (D30 = projected.d30), pela mesma função")
    void deveCoincidirComAProjecaoDoDashboard() {
        List<RecurringTransaction> recurrences = recorrencias(LocalDate.of(2026, 9, 10));
        DashboardSummary dashboard = DashboardSummary.of(s.records(), recurrences, TODAY, TODAY);

        for (Horizon horizon : Projection.HORIZONS) {
            Projection p = Projection.of(s.records(), recurrences, horizon, TODAY, false);

            assertThat(p.series().getLast().balance()).as("%s", horizon).isEqualTo(dashboard.projected().get(horizon));
            assertThat(p.series().getLast().periodEnd()).isEqualTo(horizon.endFrom(TODAY));
        }
        Projection d30 = Projection.of(s.records(), recurrences, Horizon.D30, TODAY, false);
        assertThat(d30.series()).extracting(Projection.Point::competence).containsExactly(Competence.of(2026, 10), Competence.of(2026, 11));
    }

    @Test
    @DisplayName("os vencidos (recorrência atrasada e não realizada) entram no 1º ponto, uma vez só")
    void deveIncluirOsVencidosNoPrimeiroPonto() {
        Projection p = Projection.of(s.records(), recorrencias(LocalDate.of(2026, 9, 10)), Horizon.M6, TODAY, false);

        ponto(p.series().get(0), "2026-10", "0.00", "238.00", "19762.00");
        assertThat(p.series().get(1).outflows()).isEqualTo(Money.of("1402.00"));
    }

    @Test
    @DisplayName("CA5: ocorrência realizada sai da previsão (é histórico) e a pulada não entra")
    void deveTirarARealizadaEAPulada() {
        LocalDate start = LocalDate.of(2026, 11, 10);
        RecurringTransaction internet = RecurringTransaction.restore(UUID.fromString("00000000-0000-0000-0000-00000000f001"), USER_ID,
                TransactionType.EXPENSE, Money.of("119.00"), "Internet", s.alimentacao.id(), s.nubank.id(), null, PaymentMethod.PIX,
                Schedule.of(Frequency.MONTHLY, start), List.of(start), List.of(LocalDate.of(2026, 12, 10)), true);

        Projection p = Projection.of(s.records(), List.of(internet), Horizon.M6, TODAY, false);

        ponto(p.series().get(1), "2026-11", "0.00", "1283.00", "18717.00");
        ponto(p.series().get(2), "2026-12", "0.00", "200.00", "18517.00");
        assertThat(p.series().get(3).outflows()).isEqualTo(Money.of("319.00"));
    }

    @Test
    @DisplayName("dívida com data entra uma vez (saída ou entrada); dívida sem data fica fora da série")
    void deveIncluirDividasComData() {
        s.caso6Emprestimo();
        s.caso8DividaSemMovimentacao();
        s.debts.clear();
        s.debts.add(com.moneylog.core.debt.domain.Debt.withoutMovement(UUID.randomUUID(), USER_ID, com.moneylog.core.debt.util.factory.PersonTestFactory.umPedro(),
                com.moneylog.core.debt.domain.DebtDirection.PAYABLE, Money.of("200.00"), BASE_DATE, LocalDate.of(2026, 11, 20), "Show"));
        s.debts.add(com.moneylog.core.debt.domain.Debt.withoutMovement(UUID.randomUUID(), USER_ID, com.moneylog.core.debt.util.factory.PersonTestFactory.umJoao(),
                com.moneylog.core.debt.domain.DebtDirection.RECEIVABLE, Money.of("50.00"), BASE_DATE, LocalDate.of(2026, 11, 25), "Pizza"));
        s.debts.add(com.moneylog.core.debt.domain.Debt.withoutMovement(UUID.randomUUID(), USER_ID, com.moneylog.core.debt.util.factory.PersonTestFactory.umPedro(),
                com.moneylog.core.debt.domain.DebtDirection.PAYABLE, Money.of("999.00"), BASE_DATE, null, "Sem data"));

        Projection p = Projection.of(s.records(), List.of(), Horizon.M6, TODAY, false);

        assertThat(p.series().get(1).outflows()).isEqualTo(Money.of("1483.00"));
        assertThat(p.series().get(1).inflows()).isEqualTo(Money.of("50.00"));
        assertThat(p.series().stream().map(x -> x.outflows().cents()).reduce(0L, Long::sum)).isEqualTo(Money.of("1483.00").cents() + Money.of("1000.00").cents());
    }

    @Test
    @DisplayName("horizonte fora de D30/D90/M6/M12 é recusado pelo domínio")
    void deveRecusarHorizonteNaoSuportado() {
        assertThatThrownBy(() -> Projection.of(s.records(), List.of(), Horizon.D7, TODAY, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Projection.of(s.records(), List.of(), Horizon.D60, TODAY, false)).isInstanceOf(IllegalArgumentException.class);
    }

    private FinanceScenarioFactory comHistorico() {
        FinanceScenarioFactory h = umCenarioBase();
        for (int month = 6; month <= 9; month++) {
            h.transactions.add(AccountTransactions.expense(h.header(LocalDate.of(2026, month, 3), "Mercado"), Money.of("300.00"), h.nubank, h.alimentacao, PaymentMethod.PIX));
        }
        return h;
    }

    @Test
    @DisplayName("CA4: includeTrend traz a média dos 3 meses completos (consumo novo: despesas não recorrentes + 1ª parcela), com estimate; a série base fica idêntica")
    void deveCalcularATendenciaSeparada() {
        FinanceScenarioFactory h = comHistorico();
        h.transactions.add(compraNoCalendario(h.header(LocalDate.of(2026, 7, 10), "Parcelada"), Money.of("600.00"), h.roxinho, h.alimentacao, 3));
        h.transactions.add(compraNoCalendario(h.header(LocalDate.of(2026, 8, 10), "À vista"), Money.of("90.00"), h.roxinho, h.alimentacao, 1));
        h.transactions.add(com.moneylog.core.transaction.domain.AccountTransactions.expense(h.header(LocalDate.of(2026, 9, 10), "Internet"), Money.of("119.00"), h.nubank,
                h.alimentacao, PaymentMethod.PIX));
        FinancialRecords records = h.records();

        Projection sem = Projection.of(records, List.of(), Horizon.M6, TODAY, false);
        Projection com = Projection.of(records, List.of(), Horizon.M6, TODAY, true);

        assertThat(com.trend().available()).isTrue();
        assertThat(com.trend().estimate()).isTrue();
        assertThat(com.trend().reason()).isNull();
        assertThat(com.trend().monthlyAverage()).isEqualTo(Money.of("436.33"));
        assertThat(com.trend().basedOn()).containsExactly(Competence.of(2026, 7), Competence.of(2026, 8), Competence.of(2026, 9));
        assertThat(com.series()).as("a série base não muda com a tendência").isEqualTo(sem.series());
        assertThat(com.assumptions()).isEqualTo(sem.assumptions());
        assertThat(com.trend().series()).hasSize(com.series().size());
        assertThat(com.trend().series().get(0).estimatedSpending()).as("o mês corrente já está em andamento").isEqualTo(Money.of("0.00"));
        assertThat(com.trend().series().get(2).estimatedSpending()).isEqualTo(Money.of("872.66"));
        assertThat(com.trend().series().get(2).balance()).isEqualTo(com.series().get(2).balance().subtract(Money.of("872.66")));
    }

    @Test
    @DisplayName("a tendência exclui as ocorrências de recorrência realizadas (já estão nos compromissos), parcelas 2..N e reembolsos")
    void naoDeveContarRecorrenciaNemParcelasAntigas() {
        FinanceScenarioFactory h = comHistorico();
        UUID recurrenceId = UUID.randomUUID();
        RecurringTransaction recurrence = RecurringTransaction.ofAccount(recurrenceId, USER_ID, TransactionType.EXPENSE, Money.of("119.00"), "Internet",
                h.alimentacao.id(), h.nubank.id(), PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, LocalDate.of(2026, 9, 10)));
        h.transactions.add(recurrence.realize(LocalDate.of(2026, 9, 10), UUID.randomUUID(), null, h.nubank, h.alimentacao));
        h.transactions.add(compraNoCalendario(h.header(LocalDate.of(2026, 6, 10), "Notebook"), Money.of("1200.00"), h.roxinho, h.alimentacao, 6));
        SpendingTrend trend = SpendingTrend.of(h.records(), h.records().ledger(), TODAY);

        assertThat(trend.available()).isTrue();
        assertThat(trend.average()).isEqualTo(Money.of("300.00"));
    }

    @Test
    @DisplayName("CA7: com menos de 3 meses completos de histórico, trend.available é false com INSUFFICIENT_HISTORY (não inventa média)")
    void naoDeveInventarMediaSemHistorico() {
        FinanceScenarioFactory h = umCenarioBase();
        h.transactions.add(AccountTransactions.expense(h.header(LocalDate.of(2026, 8, 20), "Mercado"), Money.of("300.00"), h.nubank, h.alimentacao, PaymentMethod.PIX));
        h.transactions.add(AccountTransactions.expense(h.header(LocalDate.of(2026, 9, 3), "Mercado"), Money.of("300.00"), h.nubank, h.alimentacao, PaymentMethod.PIX));

        Projection p = Projection.of(h.records(), List.of(), Horizon.M6, TODAY, true);

        assertThat(p.trend().available()).isFalse();
        assertThat(p.trend().reason()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(p.trend().monthlyAverage()).isNull();
        assertThat(p.trend().series()).isEmpty();
        assertThat(p.trend().basedOn()).isEmpty();
        assertThat(p.trend().estimate()).isTrue();
        assertThat(Projection.of(umCenarioBase().records(), List.of(), Horizon.M6, TODAY, true).trend().available()).as("sem nenhuma movimentação").isFalse();
    }

    @Test
    @DisplayName("histórico que começa exatamente no 1º dia do 3º mês anterior basta (com consumo nos 3 meses); um dia depois, não")
    void deveExigirTresMesesCompletos() {
        FinanceScenarioFactory exact = umCenarioBase();
        FinanceScenarioFactory late = umCenarioBase();
        for (FinanceScenarioFactory h : List.of(exact, late)) {
            h.transactions.add(AccountTransactions.expense(h.header(LocalDate.of(2026, 8, 5), "Mercado"), Money.of("300.00"), h.nubank, h.alimentacao, PaymentMethod.PIX));
            h.transactions.add(AccountTransactions.expense(h.header(LocalDate.of(2026, 9, 5), "Mercado"), Money.of("300.00"), h.nubank, h.alimentacao, PaymentMethod.PIX));
        }
        exact.transactions.add(AccountTransactions.expense(exact.header(LocalDate.of(2026, 7, 1), "Mercado"), Money.of("300.00"), exact.nubank, exact.alimentacao, PaymentMethod.PIX));
        late.transactions.add(AccountTransactions.expense(late.header(LocalDate.of(2026, 7, 2), "Mercado"), Money.of("300.00"), late.nubank, late.alimentacao, PaymentMethod.PIX));

        assertThat(SpendingTrend.of(exact.records(), exact.records().ledger(), TODAY).available()).isTrue();
        assertThat(SpendingTrend.of(late.records(), late.records().ledger(), TODAY).available()).isFalse();
    }

    @Test
    @DisplayName("S-262 (M3b): um lançamento antigo com meses vazios no meio não basta; cada um dos 3 meses precisa de consumo novo (recorrência, parcela 2..N e reembolso não contam como consumo do mês)")
    void naoDeveAceitarMesVazioNoMeioDoHistorico() {
        FinanceScenarioFactory h = umCenarioBase();
        h.transactions.add(AccountTransactions.expense(h.header(LocalDate.of(2026, 5, 3), "Antigo"), Money.of("300.00"), h.nubank, h.alimentacao, PaymentMethod.PIX));
        h.transactions.add(AccountTransactions.expense(h.header(LocalDate.of(2026, 7, 10), "Julho"), Money.of("300.00"), h.nubank, h.alimentacao, PaymentMethod.PIX));
        h.transactions.add(AccountTransactions.expense(h.header(LocalDate.of(2026, 9, 10), "Setembro"), Money.of("300.00"), h.nubank, h.alimentacao, PaymentMethod.PIX));

        SpendingTrend gap = SpendingTrend.of(h.records(), h.records().ledger(), TODAY);
        assertThat(gap.available()).as("agosto sem nenhum consumo").isFalse();
        assertThat(gap.reason()).isEqualTo(SpendingTrend.INSUFFICIENT_HISTORY);
        assertThat(gap.average()).isNull();

        h.transactions.add(AccountTransactions.expense(h.header(LocalDate.of(2026, 8, 31), "Agosto"), Money.of("30.00"), h.nubank, h.alimentacao, PaymentMethod.PIX));
        SpendingTrend filled = SpendingTrend.of(h.records(), h.records().ledger(), TODAY);
        assertThat(filled.available()).isTrue();
        assertThat(filled.average()).isEqualTo(Money.of("210.00"));
    }

    @Test
    @DisplayName("CA8 (propriedade): em 100 cenários aleatórios nenhum compromisso aparece duas vezes na série; Σ da série = comprometido/entradas do horizonte e o último saldo = projected")
    void naoDeveContarDuasVezesEm100Cenarios() {
        for (int seed = 0; seed < 100; seed++) {
            Random random = new Random(seed);
            FinanceScenarioFactory h = umCenarioBase();
            int operations = 3 + random.nextInt(10);
            for (int i = 0; i < operations; i++) {
                LocalDate date = LocalDate.of(2026, 8 + random.nextInt(5), 1 + random.nextInt(28));
                int kind = random.nextInt(6);
                if (kind == 0) {
                    h.transactions.add(compraNoCalendario(h.header(date, "c" + i), Money.ofCents(1200 + random.nextInt(900_000), CurrencyCode.BRL), h.roxinho,
                            h.alimentacao, 1 + random.nextInt(12)));
                } else if (kind == 1) {
                    h.transactions.add(AccountTransactions.expense(h.header(date, "d" + i), Money.ofCents(100 + random.nextInt(100_000), CurrencyCode.BRL), h.nubank,
                            h.alimentacao, PaymentMethod.PIX));
                } else if (kind == 3) {
                    h.transactions.add(AccountTransactions.income(h.header(date, "r" + i), Money.ofCents(100 + random.nextInt(100_000), CurrencyCode.BRL), h.nubank,
                            h.salario, PaymentMethod.PIX));
                } else if (kind == 4) {
                    RecurringTransaction realized = RecurringTransaction.ofAccount(UUID.randomUUID(), USER_ID, TransactionType.EXPENSE,
                            Money.ofCents(100 + random.nextInt(50_000), CurrencyCode.BRL), "real" + i, h.alimentacao.id(), h.nubank.id(), PaymentMethod.PIX,
                            Schedule.of(Frequency.MONTHLY, date));
                    h.transactions.add(realized.realize(date, UUID.randomUUID(), null, h.nubank, h.alimentacao));
                } else if (kind == 5) {
                    h.transactions.add(AccountTransactions.transfer(h.header(date, "t" + i), Money.ofCents(100 + random.nextInt(100_000), CurrencyCode.BRL), h.nubank, h.xp));
                } else {
                    h.debts.add(com.moneylog.core.debt.domain.Debt.withoutMovement(UUID.randomUUID(), USER_ID, com.moneylog.core.debt.util.factory.PersonTestFactory.umPedro(),
                            random.nextBoolean() ? com.moneylog.core.debt.domain.DebtDirection.PAYABLE : com.moneylog.core.debt.domain.DebtDirection.RECEIVABLE,
                            Money.ofCents(100 + random.nextInt(100_000), CurrencyCode.BRL), date, date.plusDays(random.nextInt(400)), "d" + i));
                }
            }
            List<RecurringTransaction> recurrences = new ArrayList<>();
            for (int i = random.nextInt(4); i > 0; i--) {
                recurrences.add(RecurringTransaction.ofAccount(UUID.randomUUID(), USER_ID, random.nextBoolean() ? TransactionType.EXPENSE : TransactionType.INCOME,
                        Money.ofCents(100 + random.nextInt(50_000), CurrencyCode.BRL), "r" + i, h.alimentacao.id(), h.nubank.id(), PaymentMethod.PIX,
                        Schedule.of(Frequency.values()[random.nextInt(4)], LocalDate.of(2026, 9, 1 + random.nextInt(28)))));
            }
            FinancialRecords records = h.records();
            CommitmentSchedule schedule = CommitmentSchedule.of(records, recurrences, TODAY);
            Set<String> keys = new HashSet<>();
            for (Commitment c : schedule.items()) {
                assertThat(keys.add(c.kind() + "|" + c.refId() + "|" + c.refDate() + "|" + c.dueDate()))
                        .as("seed %d: compromisso repetido %s", seed, c).isTrue();
            }
            DashboardSummary dashboard = DashboardSummary.of(records, recurrences, TODAY, TODAY);
            for (Horizon horizon : Projection.HORIZONS) {
                Projection p = Projection.of(records, recurrences, horizon, TODAY, false);
                Money out = Money.sum(p.series().stream().map(Projection.Point::outflows).toList(), CurrencyCode.BRL);
                Money in = Money.sum(p.series().stream().map(Projection.Point::inflows).toList(), CurrencyCode.BRL);
                assertThat(out).as("seed %d %s: saídas", seed, horizon).isEqualTo(schedule.committed(horizon));
                assertThat(in).as("seed %d %s: entradas", seed, horizon).isEqualTo(schedule.incoming(horizon));
                assertThat(p.series().getLast().balance()).as("seed %d %s: saldo final", seed, horizon).isEqualTo(dashboard.projected().get(horizon));
            }
        }
    }

    @Test
    @DisplayName("GoalPriority e Goal usados na meta do cenário ficam fora do cálculo (a série não lê metas)")
    void naoDeveLerMetasNaSerie() {
        Goal goal = GoalTestFactory.umaMeta(GOAL_ID, "Qualquer", "100.00", null, GoalPriority.LOW);
        FinancialRecords base = s.records();

        Projection a = Projection.of(base, List.of(), Horizon.M6, TODAY, false);
        Projection b = Projection.of(new FinancialRecords(base.accounts(), base.cards(), base.transactions(), base.debts(), List.of(goal)), List.of(),
                Horizon.M6, TODAY, false);

        assertThat(b.series()).isEqualTo(a.series());
    }
}
