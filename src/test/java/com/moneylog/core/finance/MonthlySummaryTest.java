package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.compraNoCalendario;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.MonthlySummary;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.domain.Transaction;

import static com.moneylog.core.util.Ids.USER_ID;

@DisplayName("Resumo mensal (T95, S-151): tudo calculado no core, pelo caminho único de FinancialRecords")
class MonthlySummaryTest {

    private static final LocalDate TODAY = BASE_DATE;
    private static final UUID TECNOLOGIA_ID = UUID.fromString("00000000-0000-0000-0000-00000000ca01");

    private final Category tecnologia = Category.create(TECNOLOGIA_ID, USER_ID, "Tecnologia", CategoryKind.EXPENSE);

    /**
     * Outubro de 2026: Salário 3.000,00; Pix 100,00 (Alimentação); compra de 1.200,00 em 6x no cartão (Tecnologia); aplicação de
     * 10.000,00; reembolso de 50,00 (Alimentação) em 12/10. Setembro: consumo de 150,00 e receita de 3.000,00.
     */
    private FinanceScenarioFactory cenario(boolean compraDeTecnologia) {
        FinanceScenarioFactory s = umCenarioBase();
        Category categoriaDaCompra = compraDeTecnologia ? tecnologia : s.alimentacao;
        s.transactions.add(AccountTransactions.income(s.header(LocalDate.of(2026, 10, 5), "Salário"), Money.of("3000.00"), s.nubank, s.salario,
                PaymentMethod.PIX));
        Transaction pix = s.caso1DespesaPix();
        s.transactions.add(compraNoCalendario(s.header(BASE_DATE, "Notebook"), Money.of("1200.00"), s.roxinho, categoriaDaCompra, 6));
        s.caso5Investimento();
        s.transactions.add(AccountTransactions.refundToAccount(s.header(LocalDate.of(2026, 10, 12), "Reembolso"), Money.of("50.00"), s.nubank,
                s.alimentacao, RefundOrigin.firstRefundOf(pix)));
        s.transactions.add(AccountTransactions.income(s.header(LocalDate.of(2026, 9, 5), "Salário set"), Money.of("3000.00"), s.nubank, s.salario,
                PaymentMethod.PIX));
        s.transactions.add(AccountTransactions.expense(s.header(LocalDate.of(2026, 9, 15), "Mercado set"), Money.of("150.00"), s.nubank,
                s.alimentacao, PaymentMethod.PIX));
        return s;
    }

    private static MonthlySummary summary(FinancialRecords records, String month, LocalDate today) {
        return MonthlySummary.of(records, records.ledger(), Competence.parse(month), today);
    }

    @Test
    @DisplayName("CA1: receitas 3.000, consumo 100 + 200 − 50 = 250, investimentos 10.000 fora do consumo, net 2.750, savingsRate 0,9167, maior categoria Tecnologia (200)")
    void deveCalcularOCenarioDoPlano() {
        FinancialRecords records = cenario(true).records();

        MonthlySummary s = summary(records, "2026-10", TODAY);

        assertThat(s.income()).isEqualTo(Money.of("3000.00"));
        assertThat(s.expenses()).isEqualTo(Money.of("250.00"));
        assertThat(s.investments()).isEqualTo(Money.of("10000.00"));
        assertThat(s.net()).isEqualTo(Money.of("2750.00"));
        assertThat(s.savingsRate().toPlainString()).isEqualTo("0.9167");
        assertThat(s.largestCategory().categoryId()).isEqualTo(TECNOLOGIA_ID);
        assertThat(s.largestCategory().amount()).isEqualTo(Money.of("200.00"));
    }

    @Test
    @DisplayName("CA1 parametrizado: com a compra em Alimentação, a maior categoria é Alimentação (200 + 100 − 50 = 250)")
    void deveEscolherAMaiorCategoriaDeConsumo() {
        FinanceScenarioFactory s = cenario(false);

        MonthlySummary summary = summary(s.records(), "2026-10", TODAY);

        assertThat(summary.largestCategory().categoryId()).isEqualTo(s.alimentacao.id());
        assertThat(summary.largestCategory().amount()).isEqualTo(Money.of("250.00"));
        assertThat(summary.expenses()).isEqualTo(Money.of("250.00"));
    }

    @Test
    @DisplayName("CA2: o maior gasto é o maior lançamento de consumo (a parcela de 200 \"Notebook\" 1/6), com o id da movimentação")
    void deveEscolherOMaiorGastoDaCompetencia() {
        FinanceScenarioFactory s = cenario(true);

        MonthlySummary summary = summary(s.records(), "2026-10", TODAY);

        MonthlySummary.LargestExpense largest = summary.largestExpense();
        assertThat(largest.amount()).isEqualTo(Money.of("200.00"));
        assertThat(largest.description()).isEqualTo("Notebook");
        assertThat(largest.installmentNumber()).isEqualTo(1);
        assertThat(largest.installmentTotal()).isEqualTo(6);
        assertThat(largest.transactionId()).isEqualTo(s.transactions.stream().filter(t -> "Notebook".equals(t.description())).findFirst().orElseThrow().id());
        assertThat(largest.debtId()).isNull();
        assertThat(largest.date()).isEqualTo(BASE_DATE);
        assertThat(largest.categoryId()).isEqualTo(TECNOLOGIA_ID);
    }

    @Test
    @DisplayName("CA3: card traz o consumo no crédito do mês (200) e o outstanding das faturas que vencem no mês; futureInstallments = 1.000")
    void deveTrazerCartaoEParcelasFuturas() {
        FinancialRecords records = cenario(true).records();

        MonthlySummary out = summary(records, "2026-10", TODAY);
        MonthlySummary nov = summary(records, "2026-11", TODAY);

        assertThat(out.card().consumption()).isEqualTo(Money.of("200.00"));
        assertThat(out.card().outstanding()).as("nenhuma fatura vence em outubro: a de 25/10 vence 05/11").isEqualTo(Money.of("0.00"));
        assertThat(out.futureInstallments()).isEqualTo(Money.of("1000.00"));
        assertThat(nov.card().consumption()).isEqualTo(Money.of("200.00"));
        assertThat(nov.card().outstanding()).isEqualTo(Money.of("200.00"));
        assertThat(nov.futureInstallments()).isEqualTo(Money.of("800.00"));
    }

    @Test
    @DisplayName("CA4: comparação com setembro: despesas +100,00 (+66,67%); receitas iguais → 0,00; base 0 em investimentos → percent null")
    void deveComparComOMesAnterior() {
        FinancialRecords records = cenario(true).records();

        MonthlySummary.Comparison c = summary(records, "2026-10", TODAY).comparison();

        assertThat(c.previous()).isEqualTo(Competence.of(2026, 9));
        assertThat(c.previousTotals().expenses()).isEqualTo(Money.of("150.00"));
        assertThat(c.previousTotals().income()).isEqualTo(Money.of("3000.00"));
        assertThat(c.expenses().amount()).isEqualTo(Money.of("100.00"));
        assertThat(c.expenses().percent().toPlainString()).isEqualTo("0.6667");
        assertThat(c.income().amount()).isEqualTo(Money.of("0.00"));
        assertThat(c.income().percent().toPlainString()).isEqualTo("0.0000");
        assertThat(c.investments().amount()).isEqualTo(Money.of("10000.00"));
        assertThat(c.investments().percent()).as("base 0: sem percentual").isNull();
        assertThat(c.net().amount()).isEqualTo(Money.of("-100.00"));
        assertThat(c.net().percent().toPlainString()).isEqualTo("-0.0351");
    }

    @Test
    @DisplayName("CA5: mês corrente é partial; mês passado não; mês futuro é partial e projeção")
    void deveMarcarParcialEProjecao() {
        FinancialRecords records = cenario(true).records();

        MonthlySummary corrente = summary(records, "2026-10", TODAY);
        MonthlySummary passado = summary(records, "2026-09", TODAY);
        MonthlySummary futuro = summary(records, "2026-12", TODAY);

        assertThat(corrente.partial()).isTrue();
        assertThat(corrente.isProjection()).isFalse();
        assertThat(passado.partial()).isFalse();
        assertThat(passado.isProjection()).isFalse();
        assertThat(futuro.partial()).isTrue();
        assertThat(futuro.isProjection()).isTrue();
        assertThat(futuro.expenses()).as("só o que já existe: a parcela 3/6").isEqualTo(Money.of("200.00"));
        assertThat(futuro.income()).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("CA6: sem receitas no mês, savingsRate é null (nunca divisão por zero); sem consumo, não há maior categoria nem maior gasto")
    void deveTratarMesVazio() {
        FinancialRecords records = cenario(true).records();

        MonthlySummary agosto = summary(records, "2026-08", TODAY);

        assertThat(agosto.income()).isEqualTo(Money.of("0.00"));
        assertThat(agosto.savingsRate()).isNull();
        assertThat(agosto.largestCategory()).isNull();
        assertThat(agosto.largestExpense()).isNull();
        assertThat(agosto.expenses()).isEqualTo(Money.of("0.00"));
        assertThat(agosto.comparison().expenses().percent()).isNull();
        assertThat(agosto.card().outstanding()).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("receita sem consumo: savingsRate 1,0000; consumo maior que a receita: savingsRate negativo")
    void deveCalcularATaxaDeEconomia() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.income(s.header(LocalDate.of(2026, 10, 5), "Salário"), Money.of("1000.00"), s.nubank, s.salario,
                PaymentMethod.PIX));
        assertThat(summary(s.records(), "2026-10", TODAY).savingsRate().toPlainString()).isEqualTo("1.0000");
        s.transactions.add(AccountTransactions.expense(s.header(LocalDate.of(2026, 10, 6), "Gasto"), Money.of("1500.00"), s.nubank, s.alimentacao,
                PaymentMethod.PIX));
        assertThat(summary(s.records(), "2026-10", TODAY).savingsRate().toPlainString()).isEqualTo("-0.5000");
    }

    @Test
    @DisplayName("o consumo de uma dívida PAYABLE com categoria (S-137) entra no resumo e pode ser o maior gasto, com debtId e sem movimentação")
    void deveIncluirConsumoDeDivida() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.expense(s.header(BASE_DATE, "Pix"), Money.of("30.00"), s.nubank, s.alimentacao, PaymentMethod.PIX));
        s.debts.add(com.moneylog.core.debt.domain.Debt.withoutMovement(UUID.randomUUID(), USER_ID, com.moneylog.core.debt.util.factory.PersonTestFactory.umPedro(),
                com.moneylog.core.debt.domain.DebtDirection.PAYABLE, Money.of("200.00"), BASE_DATE, null, "Jantar", s.alimentacao));

        MonthlySummary summary = summary(s.records(), "2026-10", TODAY);

        assertThat(summary.expenses()).isEqualTo(Money.of("230.00"));
        assertThat(summary.largestExpense().debtId()).isNotNull();
        assertThat(summary.largestExpense().transactionId()).isNull();
        assertThat(summary.largestExpense().description()).isEqualTo("Jantar");
        assertThat(summary.largestExpense().amount()).isEqualTo(Money.of("200.00"));
    }

    @Test
    @DisplayName("CA7: o resumo, o dashboard e a lista por competência do mesmo mês batem em receitas, consumo e net")
    void deveBaterComODashboardEComALista() {
        FinancialRecords records = cenario(true).records();
        Competence outubro = Competence.of(2026, 10);

        MonthlySummary summary = summary(records, "2026-10", TODAY);
        DashboardSummary dashboard = DashboardSummary.of(records, List.of(), LocalDate.of(2026, 10, 31), TODAY);
        EntryReport consumo = EntryReport.of(records, records.ledger(), EntryLens.CONSUMPTION, outubro);
        EntryReport receita = EntryReport.of(records, records.ledger(), EntryLens.INCOME, outubro);

        assertThat(summary.income()).isEqualTo(dashboard.month().income()).isEqualTo(receita.total());
        assertThat(summary.expenses()).isEqualTo(dashboard.month().consumption()).isEqualTo(consumo.total());
        assertThat(summary.net()).isEqualTo(dashboard.month().net());
        assertThat(summary.investments()).isEqualTo(dashboard.month().investedNet());
        assertThat(summary.card().consumption()).isEqualTo(dashboard.month().credit());
    }

    @Test
    @DisplayName("T98 CA2 (propriedade): em 100 cenários aleatórios, resumo × dashboard × lista concordam em todos os meses")
    void deveConcordarEm100CenariosAleatorios() {
        for (int seed = 0; seed < 100; seed++) {
            Random random = new Random(seed);
            FinanceScenarioFactory s = umCenarioBase();
            int operations = 5 + random.nextInt(15);
            Transaction lastExpense = null;
            for (int i = 0; i < operations; i++) {
                LocalDate date = LocalDate.of(2026, 8 + random.nextInt(4), 1 + random.nextInt(28));
                Money amount = Money.ofCents(100 + random.nextInt(500_000), com.moneylog.core.sk.money.CurrencyCode.BRL);
                switch (random.nextInt(5)) {
                    case 0 -> s.transactions.add(AccountTransactions.income(s.header(date, "r" + i), amount, s.nubank, s.salario, PaymentMethod.PIX));
                    case 1 -> {
                        lastExpense = AccountTransactions.expense(s.header(date, "d" + i), amount, s.nubank, s.alimentacao, PaymentMethod.PIX);
                        s.transactions.add(lastExpense);
                    }
                    case 2 -> s.transactions.add(compraNoCalendario(s.header(date, "c" + i), Money.ofCents(1200 + random.nextInt(900_000), com.moneylog.core.sk.money.CurrencyCode.BRL),
                            s.roxinho, s.alimentacao, 1 + random.nextInt(12)));
                    case 3 -> s.transactions.add(AccountTransactions.investment(s.header(date, "i" + i), amount, com.moneylog.core.transaction.domain.InvestmentDirection.CONTRIBUTION,
                            s.nubank, s.xpInvest));
                    default -> {
                        if (lastExpense != null && lastExpense.isActive() && lastExpense.amount().cents() > 100) {
                            s.transactions.add(AccountTransactions.refundToAccount(s.header(date, "e" + i), Money.ofCents(100, com.moneylog.core.sk.money.CurrencyCode.BRL),
                                    s.nubank, s.alimentacao, RefundOrigin.firstRefundOf(lastExpense)));
                            lastExpense = null;
                        }
                    }
                }
            }
            FinancialRecords records = s.records();
            for (int month = 8; month <= 12; month++) {
                Competence competence = Competence.of(2026, month);
                MonthlySummary summary = MonthlySummary.of(records, records.ledger(), competence, TODAY);
                ConsumptionReport report = ConsumptionReport.of(records, records.ledger(), competence);
                DashboardSummary dashboard = DashboardSummary.of(records, List.of(), competence.lastDay(), TODAY);
                assertThat(summary.income()).as("seed %d mês %d: receitas", seed, month).isEqualTo(dashboard.month().income())
                        .isEqualTo(EntryReport.of(records, records.ledger(), EntryLens.INCOME, competence).total());
                assertThat(summary.expenses()).as("seed %d mês %d: consumo", seed, month).isEqualTo(dashboard.month().consumption())
                        .isEqualTo(EntryReport.of(records, records.ledger(), EntryLens.CONSUMPTION, competence).total()).isEqualTo(report.consumption());
                assertThat(summary.net()).as("seed %d mês %d: net", seed, month).isEqualTo(dashboard.month().net());
                assertThat(summary.net()).isEqualTo(summary.income().subtract(summary.expenses()));
            }
        }
    }
}
