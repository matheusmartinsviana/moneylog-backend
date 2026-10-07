package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.compraNoCalendario;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.IncomeExpenseSeries;
import com.moneylog.core.finance.domain.IncomeExpenseSeries.Basis;
import com.moneylog.core.finance.domain.IncomeExpenseSeries.Granularity;
import com.moneylog.core.finance.domain.IncomeExpenseSeries.Point;
import com.moneylog.core.finance.domain.ReportPeriod;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.domain.Transaction;

@DisplayName("Série de entradas e saídas (T184, S-283 a S-285): livros INCOME e EXPENSE pela data efetiva do lançamento")
class SerieEntradasESaidasTest {

    private static final LocalDate TODAY = BASE_DATE;

    private static IncomeExpenseSeries serie(FinanceScenarioFactory s, ReportPeriod period, LocalDate today) {
        FinancialRecords records = s.records();
        return IncomeExpenseSeries.of(records.ledger(), period, today);
    }

    private static Point at(IncomeExpenseSeries series, LocalDate day) {
        return series.points().stream().filter(p -> !day.isBefore(p.periodStart()) && !day.isAfter(p.periodEnd())).findFirst().orElseThrow();
    }

    /** Receita 3.000 (05/10), despesa 100 (05/10) e reembolso de 50 dela (09/10), Pix 100 + notebook 1.200 em 6x (10/10), mais tudo que NÃO é consumo. */
    private FinanceScenarioFactory cenario() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.income(s.header(LocalDate.of(2026, 10, 5), "Salário"), Money.of("3000.00"), s.nubank, s.salario,
                PaymentMethod.PIX));
        Transaction original = AccountTransactions.expense(s.header(LocalDate.of(2026, 10, 5), "Mercado"), Money.of("100.00"), s.nubank,
                s.alimentacao, PaymentMethod.PIX);
        s.transactions.add(original);
        s.transactions.add(AccountTransactions.refundToAccount(s.header(LocalDate.of(2026, 10, 9), "Reembolso"), Money.of("50.00"), s.nubank,
                s.alimentacao, RefundOrigin.firstRefundOf(original)));
        s.caso1DespesaPix();
        s.caso2CompraParcelada();
        s.pagarFatura("100.00", LocalDate.of(2026, 10, 25), BASE_DATE);
        s.caso4Transferencia();
        s.caso5Investimento();
        s.caso6Emprestimo();
        s.caso7Devolucao();
        return s;
    }

    @Test
    @DisplayName("CA1: D30 só tem receita e consumo (parcela no dia dela, reembolso abate); fatura paga, transferência, aplicação e empréstimo não aparecem")
    void deveTerSoReceitaEConsumo() {
        IncomeExpenseSeries d30 = serie(cenario(), ReportPeriod.D30, TODAY);

        assertThat(d30.points()).hasSize(30);
        assertThat(at(d30, LocalDate.of(2026, 10, 5)).income()).isEqualTo(Money.of("3000.00"));
        assertThat(at(d30, LocalDate.of(2026, 10, 5)).expenses()).isEqualTo(Money.of("100.00"));
        assertThat(at(d30, LocalDate.of(2026, 10, 10)).expenses()).as("Pix 100 + a parcela 1/6 de 200, não os 1.200").isEqualTo(Money.of("300.00"));
        assertThat(at(d30, LocalDate.of(2026, 10, 10)).income()).as("devolução do empréstimo não é renda").isEqualTo(Money.of("0.00"));
        assertThat(d30.totals().income()).isEqualTo(Money.of("3000.00"));
        assertThat(d30.totals().expenses()).isEqualTo(Money.of("350.00"));
        assertThat(d30.totals().net()).as("resultado = receita − consumo").isEqualTo(Money.of("2650.00"));
        assertThat(d30.points()).allSatisfy(p -> assertThat(p.net()).isEqualTo(p.income().subtract(p.expenses())));
    }

    @Test
    @DisplayName("CA3a: dia só com reembolso tem expenses negativo e net positivo")
    void deveTerExpensesNegativoNoDiaSoComReembolso() {
        Point refundDay = at(serie(cenario(), ReportPeriod.D7, TODAY), LocalDate.of(2026, 10, 9));

        assertThat(refundDay.expenses()).isEqualTo(Money.of("-50.00"));
        assertThat(refundDay.net()).isEqualTo(Money.of("50.00"));
    }

    @Test
    @DisplayName("CA3: D7 tem exatamente 7 pontos densos de hoje−6 a hoje, o último partial, basis DATE, granularidade DAY")
    void deveTerSetePontosDensos() {
        IncomeExpenseSeries d7 = serie(cenario(), ReportPeriod.D7, TODAY);

        assertThat(d7.points()).hasSize(7);
        assertThat(d7.from()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(d7.to()).isEqualTo(TODAY);
        assertThat(d7.points().get(0).periodStart()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(d7.points().get(6).periodEnd()).isEqualTo(TODAY);
        assertThat(d7.points().stream().filter(Point::partial)).containsExactly(d7.points().get(6));
        assertThat(d7.basis()).isEqualTo(Basis.DATE);
        assertThat(d7.granularity()).isEqualTo(Granularity.DAY);
        assertThat(d7.points().get(0).income()).as("zeros incluídos").isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("CA3: M3 é semanal (segunda a domingo), cada balde recortado em [from, hoje], 13 a 15 pontos, e totals é o total da janela")
    void deveRecortarAsSemanasDoM3() {
        FinanceScenarioFactory s = cenario();
        IncomeExpenseSeries m3 = serie(s, ReportPeriod.M3, TODAY);

        assertThat(m3.from()).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(m3.to()).isEqualTo(TODAY);
        assertThat(m3.points()).hasSizeBetween(13, 15);
        assertThat(m3.points().get(0).periodStart()).as("o primeiro balde começa em from, não na segunda anterior").isEqualTo(m3.from());
        assertThat(m3.points().get(m3.points().size() - 1).periodEnd()).as("o último termina em hoje").isEqualTo(TODAY);
        for (int i = 1; i < m3.points().size(); i++) {
            assertThat(m3.points().get(i).periodStart()).as("sem buraco nem sobreposição").isEqualTo(m3.points().get(i - 1).periodEnd().plusDays(1));
            assertThat(m3.points().get(i).periodStart().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
        }
        assertThat(m3.basis()).isEqualTo(Basis.DATE);
        assertThat(m3.granularity()).isEqualTo(Granularity.WEEK);
        assertThat(m3.totals().income()).isEqualTo(Money.of("3000.00"));
        assertThat(m3.totals().expenses()).isEqualTo(Money.of("350.00"));
    }

    @Test
    @DisplayName("M12: 12 competências terminando na corrente, basis COMPETENCE, o mês corrente é inteiro e partial")
    void deveTerDozeCompetencias() {
        IncomeExpenseSeries m12 = serie(cenario(), ReportPeriod.M12, TODAY);

        assertThat(m12.points()).hasSize(12);
        assertThat(m12.from()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(m12.to()).as("a competência corrente inteira").isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(m12.basis()).isEqualTo(Basis.COMPETENCE);
        assertThat(m12.granularity()).isEqualTo(Granularity.MONTH);
        Point october = m12.points().get(11);
        assertThat(october.periodStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(october.periodEnd()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(october.partial()).isTrue();
        assertThat(m12.points().stream().filter(Point::partial)).hasSize(1);
        assertThat(october.income()).isEqualTo(Money.of("3000.00"));
        assertThat(october.expenses()).isEqualTo(Money.of("350.00"));
    }

    @Test
    @DisplayName("CA3a: lançamento agendado depois de hoje no mês corrente não aparece em DAY/WEEK e aparece no ponto MONTH (S-285)")
    void deveManterOAgendadoSoNoPontoMensal() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.expense(s.header(LocalDate.of(2026, 10, 20), "Agendada"), Money.of("80.00"), s.nubank,
                s.alimentacao, PaymentMethod.PIX));

        assertThat(serie(s, ReportPeriod.D30, TODAY).totals().expenses()).isEqualTo(Money.of("0.00"));
        assertThat(serie(s, ReportPeriod.M3, TODAY).totals().expenses()).isEqualTo(Money.of("0.00"));
        assertThat(serie(s, ReportPeriod.M12, TODAY).points().get(11).expenses()).isEqualTo(Money.of("80.00"));
        assertThat(serie(s, ReportPeriod.M12, TODAY).totals().expenses()).isEqualTo(Money.of("80.00"));
    }

    @Test
    @DisplayName("CA3a: a compra de 1.200 em 6x vira 200 em cada mês, nunca 1.200 num dia só")
    void deveEspalharAsParcelas() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();

        IncomeExpenseSeries m12 = serie(s, ReportPeriod.M12, LocalDate.of(2027, 3, 10));

        assertThat(m12.points()).filteredOn(p -> p.expenses().isPositive()).hasSize(6)
                .allSatisfy(p -> assertThat(p.expenses()).isEqualTo(Money.of("200.00")));
        assertThat(m12.totals().expenses()).isEqualTo(Money.of("1200.00"));
        assertThat(serie(s, ReportPeriod.D30, TODAY).totals().expenses()).isEqualTo(Money.of("200.00"));
    }

    @Test
    @DisplayName("CA4: movimentação estornada não conta (postings inativos)")
    void deveIgnorarEstornadas() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction pix = s.caso1DespesaPix();
        s.transactions.add(AccountTransactions.income(s.header(TODAY, "Salário"), Money.of("500.00"), s.nubank, s.salario, PaymentMethod.PIX));
        s.estornar(pix, "engano", TODAY);

        IncomeExpenseSeries d7 = serie(s, ReportPeriod.D7, TODAY);

        assertThat(d7.totals().expenses()).isEqualTo(Money.of("0.00"));
        assertThat(d7.totals().income()).isEqualTo(Money.of("500.00"));
    }

    @Test
    @DisplayName("consumo de dívida PAYABLE com categoria entra na saída do dia dela (S-137)")
    void deveContarOConsumoDeDivida() {
        FinanceScenarioFactory s = umCenarioBase();
        s.debts.add(Debt.withoutMovement(UUID.fromString("00000000-0000-0000-0000-0000000d0e01"), USER_ID, PersonTestFactory.umPedro(),
                DebtDirection.PAYABLE, Money.of("80.00"), TODAY, null, "jantar pago pelo amigo", s.alimentacao));

        assertThat(at(serie(s, ReportPeriod.D7, TODAY), TODAY).expenses()).isEqualTo(Money.of("80.00"));
    }

    @Test
    @DisplayName("sem lançamentos: série de zeros, nunca vazia")
    void deveDevolverZerosSemLancamentos() {
        IncomeExpenseSeries d30 = serie(umCenarioBase(), ReportPeriod.D30, TODAY);

        assertThat(d30.points()).hasSize(30).allSatisfy(p -> {
            assertThat(p.income().isZero()).isTrue();
            assertThat(p.expenses().isZero()).isTrue();
            assertThat(p.net().isZero()).isTrue();
        });
        assertThat(d30.totals().net().isZero()).isTrue();
    }

    @Test
    @DisplayName("fronteira de ano: hoje = 01/01 e 31/12 atravessam a virada sem ponto duplicado ou faltando")
    void deveAtravessarAViradaDeAno() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.expense(s.header(LocalDate.of(2026, 12, 31), "Ceia"), Money.of("10.00"), s.nubank,
                s.alimentacao, PaymentMethod.PIX));
        s.transactions.add(AccountTransactions.expense(s.header(LocalDate.of(2027, 1, 1), "Ano novo"), Money.of("20.00"), s.nubank,
                s.alimentacao, PaymentMethod.PIX));

        IncomeExpenseSeries onJan1 = serie(s, ReportPeriod.D7, LocalDate.of(2027, 1, 1));
        IncomeExpenseSeries onDec31 = serie(s, ReportPeriod.D7, LocalDate.of(2026, 12, 31));

        assertThat(onJan1.points()).extracting(Point::periodStart).containsExactly(LocalDate.of(2026, 12, 26), LocalDate.of(2026, 12, 27),
                LocalDate.of(2026, 12, 28), LocalDate.of(2026, 12, 29), LocalDate.of(2026, 12, 30), LocalDate.of(2026, 12, 31),
                LocalDate.of(2027, 1, 1));
        assertThat(at(onJan1, LocalDate.of(2026, 12, 31)).expenses()).isEqualTo(Money.of("10.00"));
        assertThat(at(onJan1, LocalDate.of(2027, 1, 1)).expenses()).isEqualTo(Money.of("20.00"));
        assertThat(onDec31.totals().expenses()).as("o 01/01 ainda é futuro em 31/12").isEqualTo(Money.of("10.00"));
        assertThat(serie(s, ReportPeriod.M12, LocalDate.of(2027, 1, 1)).points()).extracting(Point::periodStart).doesNotHaveDuplicates()
                .first().isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    @DisplayName("fronteira de mês curto: M3 em 28/02 e em 31/05 limita o dia (hoje−3 meses) e cobre exatamente a janela")
    void deveLimitarODiaNoM3() {
        FinanceScenarioFactory s = umCenarioBase();
        assertThat(serie(s, ReportPeriod.M3, LocalDate.of(2027, 2, 28)).from()).isEqualTo(LocalDate.of(2026, 11, 28));
        assertThat(serie(s, ReportPeriod.M3, LocalDate.of(2027, 5, 31)).from()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(serie(s, ReportPeriod.M3, LocalDate.of(2028, 5, 31)).from()).as("2028 é bissexto").isEqualTo(LocalDate.of(2028, 2, 29));
        for (LocalDate today : List.of(LocalDate.of(2027, 2, 28), LocalDate.of(2027, 5, 31), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1))) {
            IncomeExpenseSeries m3 = serie(s, ReportPeriod.M3, today);
            assertThat(m3.points()).hasSizeBetween(13, 15);
            assertThat(m3.points().get(0).periodStart()).isEqualTo(m3.from());
            assertThat(m3.points().get(m3.points().size() - 1).periodEnd()).isEqualTo(today);
        }
    }

    @Test
    @DisplayName("soma dos dias de um mês civil inteiro = ponto MONTH dele (D30 em 30/11 cobre novembro)")
    void deveFecharODiaComOMes() {
        FinanceScenarioFactory s = umCenarioBase();
        for (int day = 1; day <= 30; day++) {
            s.transactions.add(AccountTransactions.expense(s.header(LocalDate.of(2026, 11, day), "Dia " + day), Money.of(day + ".37"), s.nubank,
                    s.alimentacao, PaymentMethod.PIX));
        }
        s.transactions.add(compraNoCalendario(s.header(LocalDate.of(2026, 11, 15), "TV"), Money.of("1000.00"), s.roxinho, s.alimentacao, 3));
        LocalDate today = LocalDate.of(2026, 11, 30);

        IncomeExpenseSeries days = serie(s, ReportPeriod.D30, today);
        Point month = serie(s, ReportPeriod.M12, today).points().get(11);

        assertThat(days.points()).hasSize(30);
        assertThat(days.totals().expenses()).isEqualTo(month.expenses());
        assertThat(month.expenses()).as("30 gastos de 1,37 a 30,37 (476,10) + a parcela 1/3 de 1.000 (333,34)").isEqualTo(Money.of("809.44"));
    }
}
