package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.goal.util.GoalTestFactory.GOAL_ID;
import static com.moneylog.core.goal.util.GoalTestFactory.NOW;
import static com.moneylog.core.goal.util.GoalTestFactory.SECOND_GOAL_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.finance.app.accounts.AccountViews;
import com.moneylog.core.finance.domain.CashPosition;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.GoalReserves;
import com.moneylog.core.finance.domain.NetWorth;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.goal.domain.ReserveAllocation;
import com.moneylog.core.goal.util.GoalTestFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;

@DisplayName("Reservas de metas no dashboard e na conta (T94, S-147, S-148): reserva lógica, sem posting")
class MetasNoDashboardTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private final FinanceScenarioFactory s = cenario();

    private static FinanceScenarioFactory cenario() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        s.compraAVista1083();
        return s;
    }

    private Goal meta(UUID id, UUID accountId, String amount, LocalDate date) {
        Goal goal = GoalTestFactory.umaMeta(id, "Meta " + id.toString().substring(31), "20000.00", null, GoalPriority.MEDIUM);
        com.moneylog.core.account.domain.Account account = List.of(s.nubank, s.xp, s.xpInvest).stream().filter(a -> a.id().equals(accountId)).findFirst().orElseThrow();
        goal.reserve(UUID.randomUUID(), ReserveAllocation.of(account, Money.of(amount)), date, null, NOW);
        return goal;
    }

    private FinancialRecords records(Goal... goals) {
        FinancialRecords base = s.records();
        return new FinancialRecords(base.accounts(), base.cards(), base.transactions(), base.debts(), List.of(goals));
    }

    private DashboardSummary summary(FinancialRecords records, LocalDate asOf) {
        return DashboardSummary.of(records, List.of(), asOf, TODAY);
    }

    @Test
    @DisplayName("CA1: Nubank 20.000, fatura de 1.283 vencendo 05/11 e reserva de 8.500 na Nubank: reserved 8.500 e available 10.217; sem meta, 18.717 (caso da Etapa 3)")
    void deveAbaterOReservadoDoDisponivel() {
        DashboardSummary semMeta = summary(records(), TODAY);
        DashboardSummary comMeta = summary(records(meta(GOAL_ID, AccountTestFactory.NUBANK_ID, "8500.00", TODAY)), TODAY);

        assertThat(semMeta.balances().available()).isEqualTo(Money.of("18717.00"));
        assertThat(semMeta.balances().reserved()).isEqualTo(Money.of("0.00"));
        assertThat(comMeta.balances().reserved()).isEqualTo(Money.of("8500.00"));
        assertThat(comMeta.balances().available()).isEqualTo(Money.of("10217.00"));
        assertThat(comMeta.balances().committed30d()).isEqualTo(Money.of("1283.00"));
        assertThat(comMeta.balances().inAccounts()).as("a reserva não move dinheiro").isEqualTo(Money.of("20000.00"));
    }

    @Test
    @DisplayName("a reserva não altera saldo, consumo, receita, patrimônio nem fluxo de caixa, e não cria lançamento (garantia 1 do plano)")
    void deveManterTudoMenosODisponivel() {
        FinancialRecords semMeta = records();
        FinancialRecords comMeta = records(meta(GOAL_ID, AccountTestFactory.NUBANK_ID, "1000.00", TODAY));
        DashboardSummary antes = summary(semMeta, TODAY);
        DashboardSummary depois = summary(comMeta, TODAY);

        assertThat(depois.month()).isEqualTo(antes.month());
        assertThat(depois.cashFlow()).isEqualTo(antes.cashFlow());
        assertThat(depois.netWorth()).isEqualTo(antes.netWorth());
        assertThat(depois.committed()).isEqualTo(antes.committed());
        assertThat(depois.projected()).as("a reserva não é saída: a projeção não muda").isEqualTo(antes.projected());
        assertThat(depois.balances().inAccounts()).isEqualTo(antes.balances().inAccounts());
        assertThat(depois.balances().investments()).isEqualTo(antes.balances().investments());
        assertThat(comMeta.entries()).as("nenhum lançamento novo").isEqualTo(semMeta.entries());
        assertThat(comMeta.transactions()).isEqualTo(semMeta.transactions());
        assertThat(CashPosition.at(comMeta, comMeta.ledger(), TODAY).byAccount()).isEqualTo(CashPosition.at(semMeta, semMeta.ledger(), TODAY).byAccount());
        assertThat(ConsumptionReport.of(comMeta, comMeta.ledger(), com.moneylog.core.sk.time.Competence.from(TODAY)))
                .isEqualTo(ConsumptionReport.of(semMeta, semMeta.ledger(), com.moneylog.core.sk.time.Competence.from(TODAY)));
        assertThat(NetWorth.at(comMeta.ledger(), TODAY)).isEqualTo(NetWorth.at(semMeta.ledger(), TODAY));
        assertThat(depois.balances().available()).isEqualTo(antes.balances().available().subtract(Money.of("1000.00")));
    }

    @Test
    @DisplayName("CA2: reserva em conta INVESTMENT não muda o reservado do dashboard (já fora do disponível, S-21), mas conta no current da meta")
    void deveIgnorarReservaEmInvestimento() {
        Goal goal = meta(GOAL_ID, AccountTestFactory.XP_INVEST_ID, "500.00", TODAY);
        FinancialRecords comMeta = records(goal);

        DashboardSummary summary = summary(comMeta, TODAY);

        assertThat(summary.balances().reserved()).isEqualTo(Money.of("0.00"));
        assertThat(summary.balances().available()).isEqualTo(summary(records(), TODAY).balances().available());
        assertThat(goal.current(TODAY)).isEqualTo(Money.of("500.00"));
        GoalReserves reserves = GoalReserves.at(comMeta, CashPosition.at(comMeta, comMeta.ledger(), TODAY));
        assertThat(reserves.reservedOn(AccountTestFactory.XP_INVEST_ID)).isEqualTo(Money.of("500.00"));
        assertThat(reserves.inCash()).isEqualTo(Money.of("0.00"));
        assertThat(reserves.isUncovered(AccountTestFactory.XP_INVEST_ID)).as("a conta de investimento tem 0 de saldo: reservou acima").isTrue();
    }

    @Test
    @DisplayName("CA3: reserva de 25.000 na Nubank com saldo de 20.000: reserved 25.000, available negativo, conta e meta descobertas; nada é bloqueado")
    void deveAceitarESinalizarReservaDescoberta() {
        Goal goal = meta(GOAL_ID, AccountTestFactory.NUBANK_ID, "25000.00", TODAY);
        FinancialRecords comMeta = records(goal);

        DashboardSummary summary = summary(comMeta, TODAY);
        GoalReserves reserves = GoalReserves.at(comMeta, CashPosition.at(comMeta, comMeta.ledger(), TODAY));
        AccountDetails nubank = AccountViews.of(new Persisted<>(s.nubank, NOW, NOW), comMeta, TODAY);

        assertThat(summary.balances().reserved()).isEqualTo(Money.of("25000.00"));
        assertThat(summary.balances().available()).isEqualTo(Money.of("20000.00").subtract(Money.of("1283.00")).subtract(Money.of("25000.00")));
        assertThat(summary.balances().available().isNegative()).isTrue();
        assertThat(reserves.isUncovered(AccountTestFactory.NUBANK_ID)).isTrue();
        assertThat(nubank.uncovered()).isTrue();
        assertThat(nubank.reservedAmount()).isEqualTo(Money.of("25000.00"));
        assertThat(nubank.currentBalance()).isEqualTo(Money.of("20000.00"));
        assertThat(goal.isUncovered(reserves::isUncovered, TODAY)).isTrue();
    }

    @Test
    @DisplayName("reserva igual ao saldo não é descoberta; só passa do saldo")
    void naoDeveSinalizarReservaIgualAoSaldo() {
        FinancialRecords comMeta = records(meta(GOAL_ID, AccountTestFactory.NUBANK_ID, "20000.00", TODAY));

        GoalReserves reserves = GoalReserves.at(comMeta, CashPosition.at(comMeta, comMeta.ledger(), TODAY));

        assertThat(reserves.isUncovered(AccountTestFactory.NUBANK_ID)).isFalse();
        assertThat(reserves.uncovered()).isEmpty();
    }

    @Test
    @DisplayName("CA4: asOf no futuro usa as reservas com data até asOf; as reservas futuras só entram na data delas")
    void deveUsarAsReservasAteAsOf() {
        Goal goal = meta(GOAL_ID, AccountTestFactory.NUBANK_ID, "1000.00", TODAY);
        goal.reserve(UUID.randomUUID(), ReserveAllocation.of(s.nubank, Money.of("200.00")), LocalDate.of(2026, 11, 20), null, NOW);
        FinancialRecords comMeta = records(goal);

        assertThat(summary(comMeta, TODAY).balances().reserved()).isEqualTo(Money.of("1000.00"));
        assertThat(summary(comMeta, LocalDate.of(2026, 11, 19)).balances().reserved()).isEqualTo(Money.of("1000.00"));
        assertThat(summary(comMeta, LocalDate.of(2026, 11, 20)).balances().reserved()).isEqualTo(Money.of("1200.00"));
        assertThat(summary(comMeta, TODAY.minusDays(1)).balances().reserved()).isEqualTo(Money.of("0.00"));
        assertThat(summary(comMeta, LocalDate.of(2026, 11, 20)).isProjection()).isTrue();
    }

    @Test
    @DisplayName("duas metas na mesma conta somam no reservado; meta arquivada sai do reservado e liberar devolve o disponível")
    void deveSomarMetasEIgnorarArquivada() {
        Goal a = meta(GOAL_ID, AccountTestFactory.NUBANK_ID, "1000.00", TODAY);
        Goal b = meta(SECOND_GOAL_ID, AccountTestFactory.NUBANK_ID, "300.00", TODAY);

        assertThat(summary(records(a, b), TODAY).balances().reserved()).isEqualTo(Money.of("1300.00"));
        b.archive();
        assertThat(summary(records(a, b), TODAY).balances().reserved()).isEqualTo(Money.of("1000.00"));
        a.reserve(UUID.randomUUID(), ReserveAllocation.of(s.nubank, Money.of("-1000.00")), TODAY, null, GoalTestFactory.at(1));
        assertThat(summary(records(a, b), TODAY).balances().reserved()).isEqualTo(Money.of("0.00"));
        assertThat(summary(records(a, b), TODAY).balances().available()).isEqualTo(Money.of("18717.00"));
    }

    @Test
    @DisplayName("CA6: o saldo da conta e o resumo concordam no reservado da conta (mesmo cálculo, mesmo carregamento)")
    void deveConcordarEntreContaEResumo() {
        FinancialRecords comMeta = records(meta(GOAL_ID, AccountTestFactory.NUBANK_ID, "8500.00", TODAY));
        AccountDetails nubank = AccountViews.of(new Persisted<>(s.nubank, NOW, NOW), comMeta, TODAY);
        AccountDetails xp = AccountViews.of(new Persisted<>(s.xp, NOW, NOW), comMeta, TODAY);

        assertThat(nubank.reservedAmount()).isEqualTo(summary(comMeta, TODAY).balances().reserved()).isEqualTo(Money.of("8500.00"));
        assertThat(nubank.uncovered()).isFalse();
        assertThat(xp.reservedAmount()).isEqualTo(Money.of("0.00"));
        assertThat(USER_ID).isNotNull();
    }

    @Test
    @DisplayName("CA5: sem metas nada muda (a sobrecarga de FinancialRecords e AccountDetails mantém o comportamento das Etapas 2 a 4)")
    void naoDeveMudarSemMetas() {
        FinancialRecords legacy = s.records();

        assertThat(legacy.goals()).isEmpty();
        assertThat(summary(legacy, TODAY).balances().reserved()).isEqualTo(Money.of("0.00"));
        AccountDetails details = new AccountDetails(s.nubank, NOW, NOW, Money.of("20000.00"), true, false);
        assertThat(details.reservedAmount()).isEqualTo(Money.of("0.00"));
        assertThat(details.uncovered()).isFalse();
    }
}
