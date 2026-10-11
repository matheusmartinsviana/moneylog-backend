package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.NOW;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.card.exception.CardExceptions.OngoingCategoryRetroactiveException;
import com.moneylog.core.card.exception.CardExceptions.OngoingFirstInvoiceNotPayableException;
import com.moneylog.core.card.exception.CardExceptions.OngoingInvalidCountException;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.exception.DebtExceptions.DebtCreditorInvalidException;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.DebtOverview;
import com.moneylog.core.finance.domain.DebtOverview.CardItem;
import com.moneylog.core.finance.domain.DebtOverview.ComparisonReason;
import com.moneylog.core.finance.domain.DebtOverview.IncomeSource;
import com.moneylog.core.finance.domain.DebtOverview.PayoffReason;
import com.moneylog.core.finance.domain.DebtOverviewCalculator;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.recurrence.domain.MonthlyEstimate;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.DebtTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionSource;

@DisplayName("Visão total de dívidas (T238 a T241, ADR-013)")
class DebtOverviewTest {

    private static final Money ZERO = Money.of("0.00");

    private static DebtOverview overview(FinanceScenarioFactory s, LocalDate today, int months) {
        return overview(s.records(), today, months, ZERO, ZERO);
    }

    private static DebtOverview overview(FinancialRecords records, LocalDate today, int months, Money fixedCosts, Money income) {
        return DebtOverviewCalculator.overview(records, Map.of(PersonTestFactory.PEDRO_ID, "Pedro", PersonTestFactory.JOAO_ID, "João"),
                new MonthlyEstimate(fixedCosts, income, true), ZERO, ZERO, ZERO, today, months);
    }

    private static Transaction ongoing(FinanceScenarioFactory s, String installment, int total, int remaining, LocalDate first,
                                       com.moneylog.core.category.domain.Category category, LocalDate today) {
        Transaction tx = CardTransactions.ongoingPurchase(
                s.header(first, "Geladeira").withOrigin(com.moneylog.core.transaction.domain.TransactionOrigin.ongoing()), Money.of(installment), total, remaining, s.roxinho,
                category, s.chain(), today);
        s.transactions.add(tx);
        return tx;
    }

    @Test
    @DisplayName("1.200 em 6x hoje: cartão 1.200 (fatura 200 + futuras 1.000), plano com 6 restantes e a parcela 1 não é somada de novo")
    void deveSomarACompraParceladaUmaVezSo() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();

        DebtOverview o = overview(s, BASE_DATE, 12);

        assertThat(o.total()).isEqualTo(Money.of("1200.00"));
        assertThat(o.cards().total()).isEqualTo(Money.of("1200.00"));
        assertThat(o.cards().invoiced()).isEqualTo(Money.of("200.00"));
        assertThat(o.cards().future()).isEqualTo(Money.of("1000.00"));
        CardItem card = o.cards().items().getFirst();
        assertThat(card.plans()).hasSize(1);
        assertThat(card.plans().getFirst().remainingInstallments()).isEqualTo(6);
        assertThat(card.plans().getFirst().remaining()).isEqualTo(Money.of("1200.00"));
        assertThat(card.plans().getFirst().nextInstallmentNumber()).isEqualTo(1);
        assertThat(card.adjustment()).isEqualTo(ZERO);
        assertThat(card.otherCharges()).isEqualTo(ZERO);
    }

    @Test
    @DisplayName("pagar a fatura de 1.283 (inclui a parcela 1): o total cai 1.283, sobram 1.000 e a parcela 1 sai do plano")
    void devePagarAFaturaECairExatamenteOPago() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        s.compraAVista1083();
        LocalDate dueDay = LocalDate.of(2026, 11, 5);
        Money before = overview(s, LocalDate.of(2026, 10, 26), 12).total();
        s.caso3PagarFatura("1283.00");

        DebtOverview o = overview(s, dueDay, 12);

        assertThat(before).isEqualTo(Money.of("2283.00"));
        assertThat(o.cards().total()).isEqualTo(Money.of("1000.00"));
        CardItem card = o.cards().items().getFirst();
        assertThat(card.plans().getFirst().remainingInstallments()).isEqualTo(5);
        assertThat(card.plans().getFirst().nextInstallmentNumber()).isEqualTo(2);
        assertThat(card.plans().getFirst().remaining()).isEqualTo(Money.of("1000.00"));
        assertThat(card.otherCharges()).isEqualTo(ZERO);
    }

    @Test
    @DisplayName("pagamento parcial de 500: o total cai 500 e o ajuste do cartão é −500 (a identidade fecha)")
    void devePagamentoParcialAbaterNoAjuste() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        s.compraAVista1083();
        s.caso3PagarFatura("500.00");

        DebtOverview o = overview(s, LocalDate.of(2026, 11, 5), 12);

        assertThat(o.cards().total()).isEqualTo(Money.of("1783.00"));
        CardItem card = o.cards().items().getFirst();
        assertThat(card.adjustment()).isEqualTo(Money.of("-500.00"));
        assertThat(card.otherCharges()).isEqualTo(Money.of("1083.00"));
        assertThat(card.plans().getFirst().remaining().add(card.otherCharges()).add(card.adjustment())).isEqualTo(card.total());
    }

    @Test
    @DisplayName("pagamento a maior: o excedente vira crédito e abate a fatura seguinte (líquido), sem contar em dobro")
    void devePagamentoAMaiorAbaterAProximaFatura() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        s.pagarFatura("300.00", LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 5));

        DebtOverview o = overview(s, LocalDate.of(2026, 11, 5), 12);

        assertThat(o.cards().total()).isEqualTo(Money.of("900.00"));
        assertThat(o.cards().items().getFirst().adjustment()).isEqualTo(Money.of("-100.00"));
    }

    @Test
    @DisplayName("cancelar as parcelas restantes: as canceladas saem do plano e do total")
    void deveCancelarParcelasTirarDoTotal() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction compra = s.caso2CompraParcelada();
        CardTransactions.cancelRemainingInstallments(compra, s.chain(), BASE_DATE, NOW);

        DebtOverview o = overview(s, BASE_DATE, 12);

        assertThat(o.total()).isEqualTo(ZERO);
        assertThat(o.cards().items()).isEmpty();
    }

    @Test
    @DisplayName("reembolso no cartão em fatura não paga reduz o total")
    void deveReembolsoReduzirOTotal() {
        FinanceScenarioFactory s = umCenarioBase();
        s.compraNoCartao("500.00", 1);
        s.transactions.add(CardTransactions.refundToCard(s.header(BASE_DATE, "Estorno loja"), Money.of("120.00"), s.roxinho, s.alimentacao,
                null, s.chain(), BASE_DATE));

        DebtOverview o = overview(s, BASE_DATE, 12);

        assertThat(o.total()).isEqualTo(Money.of("380.00"));
        assertThat(o.cards().items().getFirst().adjustment()).isEqualTo(Money.of("-120.00"));
    }

    @Test
    @DisplayName("dívida PAYABLE de 200 com quitação de 50: pessoas 150; RECEIVABLE de 300 não entra e vai em receivablesInfo")
    void deveSomarSoOQueEuDevo() {
        FinanceScenarioFactory s = umCenarioBase();
        Debt pedro = s.caso8DividaSemMovimentacao();
        s.transactions.add(DebtTransactions.debtPayment(s.header(BASE_DATE, "Parte"), Money.of("50.00"), s.nubank, pedro).transaction());
        s.caso6Emprestimo();

        DebtOverview o = overview(s, BASE_DATE, 12);

        assertThat(o.people().total()).isEqualTo(Money.of("150.00"));
        assertThat(o.parts().people()).isEqualTo(Money.of("150.00"));
        assertThat(o.receivablesInfo()).isEqualTo(Money.of("300.00"));
        assertThat(o.total()).isEqualTo(Money.of("150.00"));
        assertThat(o.people().items()).singleElement().satisfies(i -> {
            assertThat(i.creditorName()).isEqualTo("Pedro");
            assertThat(i.outstanding()).isEqualTo(Money.of("150.00"));
        });
    }

    @Test
    @DisplayName("dívida perdoada, estornada ou quitada não entra")
    void deveIgnorarEncerradas() {
        FinanceScenarioFactory s = umCenarioBase();
        Debt perdoada = s.caso8DividaSemMovimentacao();
        perdoada.cancel("perdão", BASE_DATE);
        Debt estornada = Debt.withoutMovement(UUID.fromString("00000000-0000-0000-0000-00000000db09"), USER_ID,
                PersonTestFactory.umJoao(), DebtDirection.PAYABLE, Money.of("80.00"), BASE_DATE, null, "x");
        estornada.voidRecord("erro", BASE_DATE);
        s.debts.add(estornada);

        assertThat(overview(s, BASE_DATE, 12).total()).isEqualTo(ZERO);
    }

    @Test
    @DisplayName("credor livre (banco, financiamento): vai em 'outras', não em 'pessoas', e a soma das três partes é o total")
    void deveOutrasDividasFicarSeparadas() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso8DividaSemMovimentacao();
        s.debts.add(Debt.withoutMovementToCreditor(UUID.fromString("00000000-0000-0000-0000-00000000db10"), USER_ID, "Banco do Brasil",
                Money.of("30000.00"), BASE_DATE, LocalDate.of(2027, 1, 10), "Financiamento do carro"));
        s.compraNoCartao("100.00", 1);

        DebtOverview o = overview(s, BASE_DATE, 12);

        assertThat(o.other().total()).isEqualTo(Money.of("30000.00"));
        assertThat(o.other().items().getFirst().creditorName()).isEqualTo("Banco do Brasil");
        assertThat(o.other().items().getFirst().personId()).isNull();
        assertThat(o.people().total()).isEqualTo(Money.of("200.00"));
        assertThat(o.total()).isEqualTo(o.parts().cards().add(o.parts().people()).add(o.parts().other()));
        assertThat(o.total()).isEqualTo(Money.of("30300.00"));
    }

    @Test
    @DisplayName("credor livre só existe em PAYABLE e sem pessoa junto")
    void deveRecusarCredorInvalido() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-00000000db11");
        assertThatThrownBy(() -> Debt.withoutMovementToCreditor(id, USER_ID, "  ", Money.of("10.00"), BASE_DATE, null, null))
                .isInstanceOf(DebtCreditorInvalidException.class);
        assertThatThrownBy(() -> Debt.withoutMovementToCreditor(id, USER_ID, "x".repeat(101), Money.of("10.00"), BASE_DATE, null, null))
                .isInstanceOf(DebtCreditorInvalidException.class);
    }

    @Test
    @DisplayName("150 × 8 restantes de 10, próxima na fatura corrente: parcelas 3/10 a 10/10, total sobe 1.200 e é plano 'ongoing'")
    void deveCadastrarCompraEmAndamento() {
        FinanceScenarioFactory s = umCenarioBase();

        Transaction tx = ongoing(s, "150.00", 10, 8, BASE_DATE, null, BASE_DATE);

        assertThat(tx.installments()).hasSize(8);
        assertThat(tx.installments().getFirst().number()).isEqualTo(3);
        assertThat(tx.installments().getLast().number()).isEqualTo(10);
        assertThat(tx.installments()).allSatisfy(i -> assertThat(i.total()).isEqualTo(10));
        assertThat(tx.installments().getFirst().invoiceClosingDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(tx.installments().getLast().invoiceClosingDate()).isEqualTo(LocalDate.of(2027, 5, 25));
        assertThat(tx.origin().source()).isEqualTo(TransactionSource.ONGOING);
        assertThat(tx.amount()).isEqualTo(Money.of("1200.00"));

        DebtOverview o = overview(s, BASE_DATE, 12);
        assertThat(o.total()).isEqualTo(Money.of("1200.00"));
        DebtOverview.Plan plan = o.cards().items().getFirst().plans().getFirst();
        assertThat(plan.ongoing()).isTrue();
        assertThat(plan.totalInstallments()).isEqualTo(10);
        assertThat(plan.remainingInstallments()).isEqualTo(8);
        assertThat(plan.nextInstallmentNumber()).isEqualTo(3);
        assertThat(plan.installmentAmount()).isEqualTo(Money.of("150.00"));
        assertThat(plan.lastDueDate()).isEqualTo(LocalDate.of(2027, 6, 5));
    }

    @Test
    @DisplayName("sem categoria, o consumo de TODOS os meses não muda e o livro soma zero (EQUITY); com a compra pelo ledger a dívida é a parcela")
    void naoDeveCriarConsumoRetroativo() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction tx = ongoing(s, "150.00", 10, 8, BASE_DATE, null, BASE_DATE);
        FinancialRecords records = s.records();

        assertThat(records.ledger().sum(e -> e.posting().bookType() == BookType.EXPENSE)).isEqualTo(ZERO);
        for (int m = -12; m <= 24; m++) {
            Competence c = Competence.from(BASE_DATE).plusMonths(m);
            assertThat(ConsumptionReport.of(records, records.ledger(), c).consumption()).as(c.toString()).isEqualTo(ZERO);
        }
        assertThat(records.ledger().sum(e -> true)).isEqualTo(ZERO);
        assertThat(records.ledger().sum(e -> tx.id().equals(e.source().transactionId()) && e.posting().bookType() == BookType.EQUITY))
                .isEqualTo(Money.of("1200.00"));
    }

    @Test
    @DisplayName("com categoria: cada parcela vira consumo só na competência dela e nenhum mês passado muda")
    void deveConsumirSoNaCompetenciaDeCadaParcelaComCategoria() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction tx = ongoing(s, "150.00", 10, 8, BASE_DATE, s.alimentacao, BASE_DATE);
        FinancialRecords records = s.records();

        for (TransactionInstallment i : tx.installments()) {
            Money month = ConsumptionReport.of(records, records.ledger(), i.competence()).consumption();
            assertThat(month).as(i.competence().toString()).isEqualTo(Money.of("150.00"));
        }
        assertThat(ConsumptionReport.of(records, records.ledger(), Competence.of(2026, 9)).consumption()).isEqualTo(ZERO);
        assertThat(ConsumptionReport.of(records, records.ledger(), Competence.of(2026, 8)).consumption()).isEqualTo(ZERO);
    }

    @Test
    @DisplayName("com categoria, a próxima parcela em mês passado é recusada (não cria consumo no passado)")
    void deveRecusarCategoriaRetroativa() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(CardTransactions.purchase(s.header(LocalDate.of(2026, 9, 10), "Antiga"), Money.of("100.00"), s.roxinho,
                s.alimentacao, 1, com.moneylog.core.card.domain.InvoiceChain.empty(s.roxinho), LocalDate.of(2026, 9, 10)));

        assertThatThrownBy(() -> ongoing(s, "150.00", 10, 8, LocalDate.of(2026, 9, 20), s.alimentacao, BASE_DATE))
                .isInstanceOf(OngoingCategoryRetroactiveException.class);
        Transaction tx = ongoing(s, "150.00", 10, 8, LocalDate.of(2026, 9, 20), null, BASE_DATE);
        assertThat(tx.installments().getFirst().lateEntry()).isTrue();
    }

    @Test
    @DisplayName("a fatura da próxima parcela já paga: ONGOING_FIRST_INVOICE_NOT_PAYABLE; restantes > total: ONGOING_INVALID_COUNT")
    void deveRecusarFaturaPagaEContagemInvalida() {
        FinanceScenarioFactory s = umCenarioBase();

        assertThatThrownBy(() -> ongoing(s, "150.00", 10, 8, LocalDate.of(2026, 9, 20), null, BASE_DATE))
                .isInstanceOf(OngoingFirstInvoiceNotPayableException.class);
        assertThatThrownBy(() -> ongoing(s, "150.00", 10, 11, BASE_DATE, null, BASE_DATE)).isInstanceOf(OngoingInvalidCountException.class);
        assertThatThrownBy(() -> ongoing(s, "150.00", 10, 0, BASE_DATE, null, BASE_DATE)).isInstanceOf(OngoingInvalidCountException.class);
        assertThat(s.transactions).isEmpty();
    }

    @Test
    @DisplayName("estornar a compra em andamento devolve o total ao valor anterior; cancelar as restantes também")
    void deveEstornarECancelar() {
        FinanceScenarioFactory s = umCenarioBase();
        s.compraNoCartao("100.00", 1);
        Transaction tx = ongoing(s, "150.00", 10, 8, BASE_DATE, null, BASE_DATE);
        assertThat(overview(s, BASE_DATE, 12).total()).isEqualTo(Money.of("1300.00"));

        s.estornar(tx, "cadastrei errado", BASE_DATE);

        assertThat(overview(s, BASE_DATE, 12).total()).isEqualTo(Money.of("100.00"));

        Transaction other = ongoing(s, "150.00", 10, 8, BASE_DATE, null, BASE_DATE);
        CardTransactions.cancelRemainingInstallments(other, s.chain(), BASE_DATE, NOW);
        assertThat(overview(s, BASE_DATE, 12).total()).isEqualTo(Money.of("100.00"));
    }

    @Test
    @DisplayName("edição da compra em andamento sem categoria: só descrição; categoria nova é recusada")
    void deveEditarSoADescricao() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction tx = ongoing(s, "150.00", 10, 8, BASE_DATE, null, BASE_DATE);

        Transaction edited = CardTransactions.editOngoingDetails(tx, com.moneylog.core.transaction.domain.TransactionHeader.of(tx.id(),
                USER_ID, tx.date(), "Geladeira nova").withOrigin(tx.origin()));

        assertThat(edited.description()).isEqualTo("Geladeira nova");
        assertThat(edited.installments()).isEqualTo(tx.installments());
        assertThat(edited.entries()).hasSameSizeAs(tx.entries());
    }

    @Test
    @DisplayName("custo fixo 3.000 e renda 6.000: fixedCostToIncome 0.5000 e o custo fixo NÃO entra no total")
    void deveCustoFixoFicarForaDoTotal() {
        FinanceScenarioFactory s = umCenarioBase();
        s.compraNoCartao("1000.00", 1);

        DebtOverview o = overview(s.records(), BASE_DATE, 12, Money.of("3000.00"), Money.of("6000.00"));

        assertThat(o.total()).isEqualTo(Money.of("1000.00"));
        assertThat(o.fixedCostsMonthly()).isEqualTo(Money.of("3000.00"));
        assertThat(o.comparison().fixedCostToIncome().toPlainString()).isEqualTo("0.5000");
        assertThat(o.comparison().debtToIncome().toPlainString()).isEqualTo("0.1667");
        assertThat(o.income().source()).isEqualTo(IncomeSource.RECURRING_ESTIMATE);
        assertThat(o.comparison().reason()).isNull();
    }

    @Test
    @DisplayName("renda 0: razões nulas com NO_INCOME e sem erro; o último mês completo vale quando não há receita recorrente")
    void deveSemRendaNaoDividirPorZero() {
        FinanceScenarioFactory s = umCenarioBase();
        s.compraNoCartao("1000.00", 1);

        DebtOverview none = overview(s.records(), BASE_DATE, 12, Money.of("100.00"), ZERO);
        assertThat(none.income().source()).isEqualTo(IncomeSource.NONE);
        assertThat(none.income().monthly()).isNull();
        assertThat(none.comparison().debtToIncome()).isNull();
        assertThat(none.comparison().fixedCostToIncome()).isNull();
        assertThat(none.comparison().reason()).isEqualTo(ComparisonReason.NO_INCOME);

        DebtOverview lastMonth = DebtOverviewCalculator.overview(s.records(), Map.of(), new MonthlyEstimate(ZERO, ZERO, true),
                Money.of("5000.00"), ZERO, ZERO, BASE_DATE, 12);
        assertThat(lastMonth.income().source()).isEqualTo(IncomeSource.LAST_FULL_MONTH);
        assertThat(lastMonth.comparison().debtToIncome().toPlainString()).isEqualTo("0.2000");
    }

    @Test
    @DisplayName("1.200 em 6x: 200 em cada mês de vencimento (nov a abr), remainingAfter decresce até 0 e payoff em 2027-04")
    void deveGerarACurvaPorVencimento() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();

        DebtOverview.Schedule schedule = overview(s, BASE_DATE, 12).schedule();

        assertThat(schedule.points()).hasSize(12);
        assertThat(schedule.points().get(0).competence()).isEqualTo(Competence.of(2026, 10));
        assertThat(schedule.points().get(0).total()).isEqualTo(ZERO);
        for (int i = 1; i <= 6; i++) {
            assertThat(schedule.points().get(i).total()).as("ponto " + i).isEqualTo(Money.of("200.00"));
            assertThat(schedule.points().get(i).cards()).isEqualTo(Money.of("200.00"));
        }
        assertThat(schedule.points().get(1).competence()).isEqualTo(Competence.of(2026, 11));
        assertThat(schedule.points().get(6).competence()).isEqualTo(Competence.of(2027, 4));
        assertThat(schedule.points().get(1).remainingAfter()).isEqualTo(Money.of("1000.00"));
        assertThat(schedule.points().get(6).remainingAfter()).isEqualTo(ZERO);
        assertThat(schedule.payoffCompetence()).isEqualTo(Competence.of(2027, 4));
        assertThat(schedule.payoffReason()).isNull();
    }

    @Test
    @DisplayName("fatura vencida fica em overdue (fora dos pontos); dívida sem data em undated e payoff nulo com UNDATED_DEBTS")
    void deveSepararVencidoESemData() {
        FinanceScenarioFactory s = umCenarioBase();
        s.compraNoCartao("400.00", 1);
        s.debts.add(Debt.withoutMovement(UUID.fromString("00000000-0000-0000-0000-00000000db12"), USER_ID, PersonTestFactory.umPedro(),
                DebtDirection.PAYABLE, Money.of("90.00"), BASE_DATE, null, "sem data"));

        DebtOverview o = overview(s, LocalDate.of(2026, 11, 20), 12);

        assertThat(o.schedule().overdue()).isEqualTo(Money.of("400.00"));
        assertThat(o.schedule().undated()).isEqualTo(Money.of("90.00"));
        assertThat(o.schedule().payoffCompetence()).isNull();
        assertThat(o.schedule().payoffReason()).isEqualTo(PayoffReason.UNDATED_DEBTS);
        assertScheduleIdentity(o);
    }

    @Test
    @DisplayName("o que passa do horizonte vai em beyondHorizon (BEYOND_HORIZON); total 0 não tem motivo nem payoff")
    void deveSepararAlemDoHorizonte() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();

        DebtOverview o = overview(s, BASE_DATE, 3);

        assertThat(o.schedule().points()).hasSize(3);
        assertThat(o.schedule().beyondHorizon()).isEqualTo(Money.of("800.00"));
        assertThat(o.schedule().payoffReason()).isEqualTo(PayoffReason.BEYOND_HORIZON);
        assertScheduleIdentity(o);

        DebtOverview empty = overview(umCenarioBase(), BASE_DATE, 12);
        assertThat(empty.total()).isEqualTo(ZERO);
        assertThat(empty.schedule().payoffCompetence()).isNull();
        assertThat(empty.schedule().payoffReason()).isNull();
        assertThat(empty.hasRecords()).isTrue();
        assertThat(overview(new FinancialRecords(List.of(), List.of(), List.of(), List.of()), BASE_DATE, 12, ZERO, ZERO).hasRecords()).isFalse();
    }

    @Test
    @DisplayName("months fora de 1..36 é recusado pela calculadora")
    void deveRecusarHorizonteInvalido() {
        FinanceScenarioFactory s = umCenarioBase();
        assertThatThrownBy(() -> overview(s, BASE_DATE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> overview(s, BASE_DATE, 37)).isInstanceOf(IllegalArgumentException.class);
        assertThat(overview(s, BASE_DATE, 36).schedule().points()).hasSize(36);
    }

    static void assertScheduleIdentity(DebtOverview o) {
        Money sum = o.schedule().points().stream().map(DebtOverview.Point::total).reduce(ZERO, Money::add);
        assertThat(o.schedule().overdue().add(sum).add(o.schedule().undated()).add(o.schedule().beyondHorizon())).isEqualTo(o.total());
    }
}
