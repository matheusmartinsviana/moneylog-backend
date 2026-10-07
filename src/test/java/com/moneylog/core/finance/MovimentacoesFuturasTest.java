package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.domain.Commitment;
import com.moneylog.core.finance.domain.CommitmentKind;
import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.Horizon;
import com.moneylog.core.finance.domain.Projection;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.DebtTransactions;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.NewDebt;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

/**
 * Movimentação ativa com data futura é compromisso até a data chegar (S-258, BUG-QA-28): entra em {@code committed},
 * {@code projected}, {@code available} e na projeção, e nunca duas vezes (caixa = lançamentos {@code ≤ hoje}, compromisso = {@code > hoje}).
 */
@DisplayName("Movimentações com data futura como compromisso (S-258)")
class MovimentacoesFuturasTest {

    private static final LocalDate TODAY = BASE_DATE; // 10/10/2026
    private static final LocalDate OCT_15 = LocalDate.of(2026, 10, 15);

    private static DashboardSummary dashboard(FinanceScenarioFactory s, LocalDate today) {
        return DashboardSummary.of(s.records(), List.of(), today, today);
    }

    private static Money total(FinanceScenarioFactory s, LocalDate today) {
        CommitmentSchedule schedule = CommitmentSchedule.of(s.records(), List.of(), today);
        return dashboard(s, today).balances().inAccounts().add(schedule.totalIn()).subtract(schedule.totalOut());
    }

    @Test
    @DisplayName("despesa de 1.000,00 em 15/10 com hoje 10/10: compromisso de 1.000,00, projected.d30 e available caem 1.000,00 (BUG-QA-28, repro B)")
    void despesaFutura() {
        FinanceScenarioFactory s = umCenarioBase();
        DashboardSummary antes = dashboard(s, TODAY);
        s.transactions.add(AccountTransactions.expense(s.header(OCT_15, "Aluguel"), Money.of("1000.00"), s.nubank, s.alimentacao, PaymentMethod.PIX));
        DashboardSummary depois = dashboard(s, TODAY);

        assertThat(depois.balances().inAccounts()).isEqualTo(antes.balances().inAccounts());
        assertThat(depois.committed().get(Horizon.D30).subtract(antes.committed().get(Horizon.D30))).isEqualTo(Money.of("1000.00"));
        assertThat(antes.projected().get(Horizon.D30).subtract(depois.projected().get(Horizon.D30))).isEqualTo(Money.of("1000.00"));
        assertThat(antes.balances().available().subtract(depois.balances().available())).isEqualTo(Money.of("1000.00"));
        Commitment c = CommitmentSchedule.of(s.records(), List.of(), TODAY).items().getFirst();
        assertThat(c.kind()).isEqualTo(CommitmentKind.SCHEDULED_TRANSACTION);
        assertThat(c.direction()).isEqualTo(FlowDirection.OUT);
        assertThat(c.dueDate()).isEqualTo(OCT_15);
        assertThat(c.label()).isEqualTo("Aluguel");
        assertThat(Projection.of(s.records(), List.of(), Horizon.D30, TODAY, false).series().getLast().balance())
                .isEqualTo(depois.projected().get(Horizon.D30));
    }

    @Test
    @DisplayName("na data, o lançamento vira caixa e deixa de ser compromisso: o projetado não muda no dia (sem dupla contagem)")
    void naDataViraCaixa() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.expense(s.header(OCT_15, "Aluguel"), Money.of("1000.00"), s.nubank, s.alimentacao, PaymentMethod.PIX));
        Money projectedBefore = dashboard(s, OCT_15.minusDays(1)).projected().get(Horizon.D30);
        DashboardSummary onTheDay = dashboard(s, OCT_15);

        assertThat(CommitmentSchedule.of(s.records(), List.of(), OCT_15).items()).isEmpty();
        assertThat(onTheDay.committed().get(Horizon.D30)).isEqualTo(Money.of("0.00"));
        assertThat(onTheDay.projected().get(Horizon.D30)).isEqualTo(projectedBefore);
    }

    @Test
    @DisplayName("receita futura é entrada prevista; transferência entre contas de caixa e compra no cartão com data futura não geram item próprio")
    void receitaTransferenciaECartao() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.income(s.header(OCT_15, "Salário"), Money.of("3000.00"), s.nubank, s.salario, PaymentMethod.PIX));
        s.transactions.add(AccountTransactions.transfer(s.header(OCT_15, "Mover"), Money.of("500.00"), s.nubank, s.xp));
        Money outBefore = CommitmentSchedule.of(s.records(), List.of(), TODAY).totalOut();
        s.compraNoCartao("600.00", 1);

        CommitmentSchedule schedule = CommitmentSchedule.of(s.records(), List.of(), TODAY);
        assertThat(schedule.items().stream().filter(c -> c.kind() == CommitmentKind.SCHEDULED_TRANSACTION)).singleElement().satisfies(c -> {
            assertThat(c.direction()).isEqualTo(FlowDirection.IN);
            assertThat(c.amount()).isEqualTo(Money.of("3000.00"));
        });
        assertThat(schedule.totalOut().subtract(outBefore)).isEqualTo(Money.of("600.00")); // só a previsão de fatura
        assertThat(schedule.items().stream().filter(c -> c.kind() == CommitmentKind.INVOICE_FORECAST)).hasSize(1);
    }

    @Test
    @DisplayName("aplicação futura da conta de caixa para investimento é saída; resgate futuro é entrada")
    void investimentoFuturo() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.investment(s.header(OCT_15, "CDB"), Money.of("700.00"), InvestmentDirection.CONTRIBUTION, s.nubank, s.xpInvest));

        CommitmentSchedule schedule = CommitmentSchedule.of(s.records(), List.of(), TODAY);
        assertThat(schedule.items()).singleElement().satisfies(c -> {
            assertThat(c.direction()).isEqualTo(FlowDirection.OUT);
            assertThat(c.amount()).isEqualTo(Money.of("700.00"));
        });
    }

    @Test
    @DisplayName("movimentação estornada com data futura não é compromisso")
    void estornadaNaoEntra() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction tx = AccountTransactions.expense(s.header(OCT_15, "Aluguel"), Money.of("1000.00"), s.nubank, s.alimentacao, PaymentMethod.PIX);
        s.transactions.add(tx);
        s.estornar(tx, "engano", TODAY);

        assertThat(CommitmentSchedule.of(s.records(), List.of(), TODAY).items()).isEmpty();
    }

    @Test
    @DisplayName("pagamento de fatura e quitação de dívida com data futura: o saldo da fatura/dívida cai e o caixa entra como compromisso, o total de saídas não muda")
    void pagamentosFuturosNaoDobram() {
        FinanceScenarioFactory s = umCenarioBase();
        s.compraNoCartao("900.00", 1); // fatura ref. nov (fecha 25/10), vence 05/11
        Money before = total(s, TODAY);
        CommitmentSchedule scheduleBefore = CommitmentSchedule.of(s.records(), List.of(), TODAY);
        s.pagarFatura("400.00", LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 5));
        CommitmentSchedule after = CommitmentSchedule.of(s.records(), List.of(), TODAY);

        assertThat(after.totalOut()).isEqualTo(scheduleBefore.totalOut());
        assertThat(after.items().stream().map(Commitment::kind)).containsExactlyInAnyOrder(CommitmentKind.INVOICE_FORECAST, CommitmentKind.SCHEDULED_TRANSACTION);
        assertThat(total(s, TODAY)).isEqualTo(before);

        var borrowed = DebtTransactions.moneyBorrowed(s.header(TODAY, "Empréstimo"), Money.of("1000.00"), s.nubank,
                new NewDebt(FinanceScenarioFactory.PEDRO_DEBT_ID, PersonTestFactory.umPedro(), LocalDate.of(2026, 12, 1), null));
        s.transactions.add(borrowed.transaction());
        s.debts.add(borrowed.debt());
        Money beforeDebt = total(s, TODAY);
        Money outBeforeDebt = CommitmentSchedule.of(s.records(), List.of(), TODAY).totalOut();
        var payment = DebtTransactions.debtPayment(s.header(OCT_15, "Pago ao Pedro"), Money.of("300.00"), s.nubank, borrowed.debt());
        s.transactions.add(payment.transaction());

        assertThat(CommitmentSchedule.of(s.records(), List.of(), TODAY).totalOut()).isEqualTo(outBeforeDebt);
        assertThat(total(s, TODAY)).isEqualTo(beforeDebt);
    }

    @Test
    @DisplayName("propriedade (M3): com datas variadas e todos os tipos (despesa, receita, transferência, aplicação, compra parcelada, dívida, pagamento parcial de fatura, ocorrência realizada, estorno), 'em contas + entradas − saídas' dos compromissos não depende do dia em que se olha")
    void totalNaoDependeDoRelogio() {
        int futureItems = 0;
        int partialPayments = 0;
        int realized = 0;
        int voids = 0;
        for (int seed = 0; seed < 300; seed++) {
            Random random = new Random(seed);
            FinanceScenarioFactory s = umCenarioBase();
            List<Transaction> accountExpenses = new java.util.ArrayList<>();
            int n = 3 + random.nextInt(12);
            for (int i = 0; i < n; i++) {
                LocalDate date = TODAY.plusDays(random.nextInt(81) - 9);
                Money amount = Money.ofCents(100 + random.nextInt(200_000), CurrencyCode.BRL);
                switch (random.nextInt(10)) {
                    case 0, 1 -> {
                        Transaction tx = AccountTransactions.expense(s.header(date, "d" + i), amount, s.nubank, s.alimentacao, PaymentMethod.PIX);
                        s.transactions.add(tx);
                        accountExpenses.add(tx);
                    }
                    case 2 -> s.transactions.add(AccountTransactions.income(s.header(date, "r" + i), amount, s.nubank, s.salario, PaymentMethod.PIX));
                    case 3 -> s.transactions.add(AccountTransactions.transfer(s.header(date, "t" + i), amount, s.nubank, s.xp));
                    case 4 -> s.transactions.add(AccountTransactions.investment(s.header(date, "i" + i), amount,
                            random.nextBoolean() ? InvestmentDirection.CONTRIBUTION : InvestmentDirection.REDEMPTION, s.nubank, s.xpInvest));
                    case 5 -> s.compraNoCartao(amount.toPlainString(), 1 + random.nextInt(6));
                    case 6 -> {
                        // pagamento parcial de fatura, com data passada, de hoje ou futura
                        var invoices = s.chain().invoices().stream().filter(inv -> inv.outstanding().isPositive()).toList();
                        if (!invoices.isEmpty()) {
                            var invoice = invoices.get(random.nextInt(invoices.size()));
                            long part = 1 + random.nextInt((int) Math.min(invoice.outstanding().cents(), Integer.MAX_VALUE));
                            try {
                                s.pagarFatura(Money.ofCents(part, CurrencyCode.BRL).toPlainString(), invoice.closingDate(), date);
                                partialPayments++;
                            } catch (RuntimeException refused) {
                                // fatura que o domínio não deixa pagar assim: segue sem pagamento
                            }
                        }
                    }
                    case 7 -> {
                        // ocorrência realizada (adiantada ou não): vira movimentação e sai das pendentes
                        RecurringTransaction recurrence = RecurringTransaction.ofAccount(UUID.randomUUID(), com.moneylog.core.util.Ids.USER_ID, TransactionType.EXPENSE, amount,
                                "rec" + i, s.alimentacao.id(), s.nubank.id(), PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, date));
                        s.transactions.add(recurrence.realize(date, UUID.randomUUID(), null, s.nubank, s.alimentacao));
                        realized++;
                    }
                    case 8 -> {
                        if (!accountExpenses.isEmpty()) {
                            Transaction tx = accountExpenses.remove(random.nextInt(accountExpenses.size()));
                            try {
                                s.estornar(tx, "engano", TODAY);
                                voids++;
                            } catch (RuntimeException refused) {
                                // estorno recusado pelo domínio
                            }
                        }
                    }
                    default -> s.debts.add(Debt.withoutMovement(java.util.UUID.randomUUID(), com.moneylog.core.util.Ids.USER_ID, PersonTestFactory.umPedro(),
                            random.nextBoolean() ? com.moneylog.core.debt.domain.DebtDirection.PAYABLE : com.moneylog.core.debt.domain.DebtDirection.RECEIVABLE,
                            amount, date, date.plusDays(random.nextInt(300)), "p" + i));
                }
            }
            Money reference = total(s, TODAY.minusDays(9)); // 01/10: a conta já foi aberta (saldo inicial não é movimentação)
            for (int d = -9; d <= 75; d += 3) {
                LocalDate today = TODAY.plusDays(d);
                assertThat(total(s, today)).as("seed %d, hoje %s", seed, today).isEqualTo(reference);
                CommitmentSchedule schedule = CommitmentSchedule.of(s.records(), List.of(), today);
                assertThat(schedule.items()).as("seed %d, hoje %s: movimentação com data passada não é compromisso", seed, today)
                        .filteredOn(c -> c.kind() == CommitmentKind.SCHEDULED_TRANSACTION).allMatch(c -> c.dueDate().isAfter(today));
                futureItems += (int) schedule.items().stream().filter(c -> c.kind() == CommitmentKind.SCHEDULED_TRANSACTION).count();
                // cada movimentação ativa aparece no máximo uma vez por data como compromisso (nunca duas vezes)
                assertThat(schedule.items().stream().filter(c -> c.kind() == CommitmentKind.SCHEDULED_TRANSACTION).map(c -> c.refId() + "|" + c.dueDate()))
                        .doesNotHaveDuplicates();
                // as lentes concordam: o último ponto da projeção de cada horizonte é o projected do dashboard
                DashboardSummary dashboard = dashboard(s, today);
                for (Horizon horizon : Projection.HORIZONS) {
                    assertThat(Projection.of(s.records(), List.of(), horizon, today, false).series().getLast().balance())
                            .as("seed %d, hoje %s, %s", seed, today, horizon).isEqualTo(dashboard.projected().get(horizon));
                }
            }
        }
        assertThat(futureItems).as("variância: houve compromissos de movimentação futura").isGreaterThan(1000);
        assertThat(partialPayments).as("variância: pagamentos parciais de fatura").isGreaterThan(20);
        assertThat(realized).as("variância: ocorrências realizadas").isGreaterThan(50);
        assertThat(voids).as("variância: estornos").isGreaterThan(10);
    }
}
