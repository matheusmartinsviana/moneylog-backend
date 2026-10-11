package com.moneylog.core.insights.app;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.insights.domain.DailyInsights;
import com.moneylog.core.insights.domain.DayFacts;
import com.moneylog.core.insights.domain.Insight;
import com.moneylog.core.insights.domain.InsightSink;
import com.moneylog.core.insights.domain.MonthFacts;
import com.moneylog.core.insights.domain.MonthlyInsights;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.domain.TransactionType;

/**
 * O contexto só <b>classifica</b> as linhas que as calculadoras do razão já devolvem (PR 01 regra 10), em classes disjuntas: nenhum centavo é contado
 * duas vezes. Cenários 100% sintéticos (cenário base de D1 §3, relógio 10/10/2026).
 */
@DisplayName("InsightContext (T286): classificação disjunta, competência da renda e casos do PR 01")
class InsightContextTest {

    private static final Competence OCT = Competence.of(2026, 10);
    private static final Competence SEP = Competence.of(2026, 9);

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static Money m(String v) {
        return Money.of(v);
    }

    private static InsightContext context(FinanceScenarioFactory s, List<RecurringTransaction> recurrences, LocalDate today) {
        return new InsightContext(USER_ID, today, s.records(), recurrences, Map.of(s.alimentacao.id(), "Alimentação"), List::of);
    }

    private static InsightContext context(FinanceScenarioFactory s) {
        return context(s, List.of(), BASE_DATE);
    }

    private static RecurringTransaction aluguel(FinanceScenarioFactory s, String amount, LocalDate start) {
        return RecurringTransaction.ofAccount(UUID.fromString("00000000-0000-0000-0000-0000000c0c01"), USER_ID, TransactionType.EXPENSE, m(amount),
                "Aluguel", s.alimentacao.id(), s.nubank.id(), PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, start));
    }

    // ---------------------------------------------------------------- tabela de referência do PR 01

    @Test
    @DisplayName("PR 01: Pix 100 + notebook 1.200 em 6x + pagar fatura + transferência + investimento + empréstimo + devolução: consumo de outubro = 100 + 200")
    void deveSeguirATabelaDoPr01() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso1DespesaPix();
        s.caso2CompraParcelada();
        s.pagarFatura("100.00", d("2026-10-25"), BASE_DATE);
        s.caso4Transferencia();
        s.caso5Investimento();
        s.caso6Emprestimo();
        s.caso7Devolucao();
        InsightContext context = context(s);

        MonthFacts facts = context.monthFacts(OCT);

        assertThat(facts.current().consumption()).as("só o Pix e a parcela 1/6, nunca os 1.200").isEqualTo(m("300.00"));
        assertThat(facts.installments()).isEqualTo(m("200.00"));
        assertThat(facts.current().income()).as("devolução do empréstimo não é renda").isEqualTo(m("0.00"));
        assertThat(facts.current().consumption()).isEqualTo(ConsumptionReport.of(s.records(), s.records().ledger(), OCT).consumption());
        assertThat(facts.installmentLoad()).as("200 por mês nas 6 competências, depois zero").extracting(Money::cents)
                .containsExactly(20_000L, 20_000L, 20_000L, 20_000L, 20_000L, 20_000L, 0L, 0L, 0L, 0L, 0L, 0L);
        assertThat(facts.dailyVariable()).as("gasto variável de 1 a 10: o Pix de 100 no dia 10; a parcela não é variável").hasSize(10);
        assertThat(facts.dailyVariable().get(9)).isEqualTo(m("100.00"));
        assertThat(facts.dailyVariable().stream().limit(9)).allMatch(Money::isZero);
        assertThat(facts.cards()).singleElement().satisfies(c -> {
            assertThat(c.limit()).isEqualTo(m("10000.00"));
            assertThat(c.used()).as("o limite consumido é a compra inteira (S-46)").isEqualTo(m("1100.00"));
        });
    }

    @Test
    @DisplayName("as quatro classes (parcela, recorrência, dívida, variável) somam exatamente o consumo do mês: nenhuma linha em duas classes")
    void deveParticionarOConsumoSemDuplaContagem() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso1DespesaPix();
        s.compraNoCartao("1800.00", 2);
        s.transactions.add(AccountTransactions.expense(s.header(d("2026-10-05"), "Aluguel").withOrigin(TransactionOrigin.recurrence(
                UUID.fromString("00000000-0000-0000-0000-0000000c0c01"), d("2026-10-05"))), m("1500.00"), s.nubank, s.alimentacao, PaymentMethod.PIX));
        s.caso8DividaSemMovimentacao();
        InsightContext context = context(s);

        InsightContext.Classified c = context.classified(OCT);

        assertThat(c.installments().add(c.recurring()).add(c.debt()).add(c.variable())).isEqualTo(c.consumption());
        assertThat(c.installments()).isEqualTo(m("900.00"));
        assertThat(c.recurring()).isEqualTo(m("1500.00"));
        assertThat(c.variable()).isEqualTo(m("100.00"));
        assertThat(c.lines()).as("uma linha por posting de consumo: pix, 1 parcela, aluguel").hasSize(3);
    }

    // ---------------------------------------------------------------- fixos 1.500 + parcelas 900 / renda 4.000 = 60%

    @Test
    @DisplayName("fixos 1.500 (recorrência realizada) + parcelas 900 com renda 4.000: índice 60% ATTENTION, a parcela entra uma vez só")
    void deveCalcularOIndiceSemContarAParcelaDuasVezes() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.income(s.header(d("2026-10-02"), "Folha"), m("4000.00"), s.nubank, s.salario, PaymentMethod.PIX));
        s.transactions.add(AccountTransactions.expense(s.header(d("2026-10-05"), "Aluguel").withOrigin(TransactionOrigin.recurrence(
                UUID.fromString("00000000-0000-0000-0000-0000000c0c01"), d("2026-10-05"))), m("1500.00"), s.nubank, s.alimentacao, PaymentMethod.PIX));
        s.compraNoCartao("1800.00", 2);
        InsightContext context = context(s);

        MonthFacts facts = context.monthFacts(OCT);
        InsightSink sink = new InsightSink();
        MonthlyInsights.compute(facts, sink);

        assertThat(facts.recurringOut()).isEqualTo(m("1500.00"));
        assertThat(facts.installments()).isEqualTo(m("900.00"));
        Insight index = sink.insights().stream().filter(i -> i.type().equals("COMMITMENT_INDEX")).findFirst().orElseThrow();
        assertThat(index.params()).containsEntry("percent", "60.00");
        assertThat(index.severity()).isEqualTo(Insight.Severity.ATTENTION);
    }

    @Test
    @DisplayName("ocorrência de recorrência ainda prevista entra uma vez (pendente), e a realizada não é somada de novo como pendente")
    void deveSepararRecorrenciaRealizadaDaPendente() {
        FinanceScenarioFactory s = umCenarioBase();
        RecurringTransaction rent = aluguel(s, "700.00", d("2026-10-05"));
        // a de 05/10 foi realizada (movimentação com origem na recorrência); a de 05/11 ainda é prevista
        s.transactions.add(AccountTransactions.expense(s.header(d("2026-10-05"), "Aluguel").withOrigin(TransactionOrigin.recurrence(rent.id(),
                d("2026-10-05"))), m("700.00"), s.nubank, s.alimentacao, PaymentMethod.PIX));
        RecurringTransaction pending = aluguel(s, "300.00", d("2026-10-25"));

        MonthFacts facts = context(s, List.of(pending), BASE_DATE).monthFacts(OCT);

        assertThat(facts.budget().pendingRecurring()).as("só a de 25/10; a de novembro é de outro mês").isEqualTo(m("300.00"));
        assertThat(facts.recurringOut()).as("700 realizada + 300 prevista").isEqualTo(m("1000.00"));
    }

    // ---------------------------------------------------------------- renda por competência (ADR-017)

    @Test
    @DisplayName("adiantamento de 16/09 que é renda de outubro: cai na competência de outubro, setembro fica sem renda e o caixa não muda")
    void deveContarARendaNaCompetencia() {
        FinanceScenarioFactory with = salarioDividido(true);
        FinanceScenarioFactory without = salarioDividido(false);

        MonthFacts out = context(with).monthFacts(OCT);
        MonthFacts set = context(with).monthFacts(SEP);

        assertThat(out.current().income()).isEqualTo(m("4000.00"));
        assertThat(out.budget().received()).as("recebida até hoje (16/09 e 02/10) por competência de outubro").isEqualTo(m("4000.00"));
        assertThat(set.current().income()).isEqualTo(m("0.00"));
        assertThat(out.cashBalance()).as("o caixa segue a data real: igual com ou sem competência").isEqualTo(context(without).monthFacts(OCT).cashBalance());
        // sem competência declarada, o adiantamento é renda de setembro
        assertThat(context(without).monthFacts(SEP).current().income()).isEqualTo(m("1500.00"));
        assertThat(context(without).monthFacts(OCT).current().income()).isEqualTo(m("2500.00"));
    }

    private static FinanceScenarioFactory salarioDividido(boolean comCompetencia) {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.income(s.header(d("2026-09-16"), "Adiantamento"), m("1500.00"), s.nubank, s.salario,
                PaymentMethod.PIX, comCompetencia ? OCT : null));
        s.transactions.add(AccountTransactions.income(s.header(d("2026-10-02"), "Folha"), m("2500.00"), s.nubank, s.salario, PaymentMethod.PIX));
        return s;
    }

    @Test
    @DisplayName("renda de outubro ainda não recebida (data futura): não entra na renda recebida de hoje")
    void naoDeveContarRendaFutura() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.income(s.header(d("2026-10-25"), "Folha"), m("4000.00"), s.nubank, s.salario, PaymentMethod.PIX));

        MonthFacts facts = context(s).monthFacts(OCT);

        assertThat(facts.current().income()).as("a competência é de outubro").isEqualTo(m("4000.00"));
        assertThat(facts.budget().received()).as("mas ainda não foi recebida em 10/10").isEqualTo(m("0.00"));
    }

    // ---------------------------------------------------------------- reembolsos e terceiros (ADR-014)

    @Test
    @DisplayName("despesa de 100 e reembolso de 100 (terceiro que devolveu): consumo líquido da categoria vira 0 e o mês fecha em 0")
    void deveAbaterOReembolsoNaCategoria() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction original = s.caso1DespesaPix();
        s.transactions.add(AccountTransactions.refundToAccount(s.header(BASE_DATE, "Reembolso"), m("100.00"), s.nubank, s.alimentacao,
                RefundOrigin.firstRefundOf(original)));

        MonthFacts facts = context(s).monthFacts(OCT);

        assertThat(facts.current().consumption()).isEqualTo(m("0.00"));
        assertThat(facts.current().byCategory().getOrDefault(s.alimentacao.id(), m("0.00"))).isEqualTo(m("0.00"));
        assertThat(facts.dailyVariable().get(9)).as("o dia 10 tem +100 e −100").isEqualTo(m("0.00"));
    }

    @Test
    @DisplayName("reembolso maior que o gasto do mês (veio de uma compra de outro mês): categoria líquida negativa, tratada como zero nas comparações")
    void deveTratarCategoriaLiquidaNegativaComoZero() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction original = AccountTransactions.expense(s.header(d("2026-09-20"), "Mercado"), m("300.00"), s.nubank, s.alimentacao, PaymentMethod.PIX);
        s.transactions.add(original);
        s.transactions.add(AccountTransactions.refundToAccount(s.header(BASE_DATE, "Reembolso"), m("100.00"), s.nubank, s.alimentacao,
                RefundOrigin.firstRefundOf(original)));

        MonthFacts facts = context(s).monthFacts(OCT);

        assertThat(facts.current().byCategory().get(s.alimentacao.id())).isEqualTo(m("-100.00"));
        assertThat(facts.current().categoryFloorZero(s.alimentacao.id())).isEqualTo(m("0.00"));
        InsightSink sink = new InsightSink();
        MonthlyInsights.compute(facts, sink);
        assertThat(sink.insights()).noneMatch(i -> i.type().equals("CATEGORY_DELTA"));
    }

    // ---------------------------------------------------------------- fatura histórica

    @Test
    @DisplayName("fatura histórica (só o pagamento de uma fatura passada): sem consumo em nenhum mês")
    void naoDeveContarConsumoDeFaturaHistorica() {
        FinanceScenarioFactory s = umCenarioBase();
        LocalDate today = d("2026-10-30");
        LocalDate closing = d("2026-08-25");
        Transaction item = com.moneylog.core.transaction.domain.CardTransactions.historicalInvoiceItem(
                com.moneylog.core.transaction.domain.TransactionHeader.of(UUID.randomUUID(), USER_ID, closing, "Fatura histórica")
                        .withOrigin(TransactionOrigin.historical()), m("1000.00"), s.roxinho, closing, s.chain(), today);
        s.transactions.add(item);
        s.transactions.add(com.moneylog.core.transaction.domain.CardTransactions.invoicePayment(
                com.moneylog.core.transaction.domain.TransactionHeader.of(UUID.randomUUID(), USER_ID, d("2026-09-03"), "Fatura"), m("1000.00"), s.nubank,
                s.roxinho, closing, s.chain(), today));
        InsightContext context = context(s, List.of(), today);

        for (Competence month : List.of(Competence.of(2026, 8), SEP, OCT)) {
            assertThat(context.monthFacts(month).current().consumption()).as("consumo de " + month).isEqualTo(m("0.00"));
        }
        assertThat(context.monthFacts(OCT).installments()).isEqualTo(m("0.00"));
    }

    // ---------------------------------------------------------------- do dia

    @Test
    @DisplayName("insumos do dia: parcela não é gasto variável, vencimentos da semana saem da agenda e receber de terceiro não é entrada do fluxo")
    void deveMontarOsInsumosDoDia() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.income(s.header(d("2026-10-02"), "Folha"), m("4000.00"), s.nubank, s.salario, PaymentMethod.PIX));
        s.caso1DespesaPix();
        s.caso2CompraParcelada();
        s.caso6Emprestimo();
        InsightContext context = context(s);

        DayFacts day = context.dayFacts(BASE_DATE);

        assertThat(day.variableToday()).as("só o Pix; a parcela 1/6 do notebook não é variável").isEqualTo(m("100.00"));
        assertThat(day.budget().received()).isEqualTo(m("4000.00"));
        assertThat(day.budget().spentSoFar()).isEqualTo(m("300.00"));
        assertThat(day.todayEntries()).singleElement().satisfies(e -> assertThat(e.amount()).isEqualTo(m("100.00")));
        assertThat(day.events()).as("o empréstimo concedido (a receber de João) nunca é entrada").noneMatch(e -> e.signedAmount().equals(m("300.00")));
        InsightSink sink = new InsightSink();
        DailyInsights.compute(day, sink);
        Insight budget = sink.insights().stream().filter(i -> i.type().equals("DAILY_BUDGET")).findFirst().orElseThrow();
        // 4.000 recebidos - 300 consumidos = 3.700 em 22 dias (10 a 31 de outubro) = 168,18
        assertThat(budget.params()).containsEntry("daysLeft", "22").containsEntry("free", "3700.00").containsEntry("amount", "168.18");
    }

    @Test
    @DisplayName("determinismo: a mesma entrada devolve os mesmos fatos duas vezes")
    void deveSerDeterministico() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso1DespesaPix();
        s.caso2CompraParcelada();
        FinancialRecords records = s.records();
        InsightContext a = new InsightContext(USER_ID, BASE_DATE, records, List.of(), Map.of(), List::of);
        InsightContext b = new InsightContext(USER_ID, BASE_DATE, records, List.of(), Map.of(), List::of);

        assertThat(a.monthFacts(OCT)).isEqualTo(b.monthFacts(OCT));
        assertThat(a.dayFacts(BASE_DATE)).isEqualTo(b.dayFacts(BASE_DATE));
    }
}
