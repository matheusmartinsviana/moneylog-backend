package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.NOW;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.domain.Bucket;
import com.moneylog.core.finance.domain.CashFlowReport;
import com.moneylog.core.finance.domain.Commitment;
import com.moneylog.core.finance.domain.CommitmentKind;
import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Horizon;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

@DisplayName("Compromissos, horizontes, disponível e DashboardSummary (T8b)")
class DashboardSummaryTest {

    private static final LocalDate NOV_CLOSING = LocalDate.of(2026, 10, 25);

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static CommitmentSchedule agenda(FinanceScenarioFactory s, LocalDate hoje) {
        return CommitmentSchedule.of(s.records(), List.of(), hoje);
    }

    @Test
    @DisplayName("CA2: 1.200 em 6x em 10/10 → 1.200 comprometidos (200 + 1.000); parcelas só aninhadas e informativas; cancelar as restantes reduz 1.000")
    void deveAninharParcelasEReduzirNoCancelamento() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction compra = s.caso2CompraParcelada();
        CommitmentSchedule agenda = agenda(s, BASE_DATE);

        assertThat(agenda.totalOut()).isEqualTo(Money.of("1200.00"));
        assertThat(agenda.items()).noneMatch(c -> c.kind() == CommitmentKind.INSTALLMENT);
        Commitment nov = agenda.items().getFirst();
        assertThat(nov.items()).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo(CommitmentKind.INSTALLMENT);
            assertThat(p.informational()).isTrue();
            assertThat(p.label()).isEqualTo("Notebook 1/6");
            assertThat(p.amount()).isEqualTo(Money.of("200.00"));
        });

        LocalDate hoje = d("2026-10-26");
        Money antes = agenda(s, hoje).totalOut();
        CardTransactions.cancelRemainingInstallments(compra, s.chain(), hoje, NOW);
        assertThat(antes.subtract(agenda(s, hoje).totalOut())).isEqualTo(Money.of("1000.00"));
    }

    @Test
    @DisplayName("CA3: fatura ref. nov paga com 17 a mais → a previsão da ref. dez cai de 200 para 183")
    void deveAbaterOCreditoTransportadoNaPrevisao() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        LocalDate hoje = d("2026-11-05");
        assertThat(previsaoDez(agenda(s, hoje))).isEqualTo(Money.of("200.00"));
        s.pagarFatura("217.00", NOV_CLOSING, hoje);
        assertThat(previsaoDez(agenda(s, hoje))).isEqualTo(Money.of("183.00"));
    }

    private static Money previsaoDez(CommitmentSchedule agenda) {
        return agenda.items().stream().filter(c -> d("2026-11-25").equals(c.refDate())).findFirst().orElseThrow().amount();
    }

    @Test
    @DisplayName("CA4: horizontes cumulativos; vencidos em todos; dívida sem data em undated; ocorrência de recorrência no horizonte da data")
    void deveCalcularOsHorizontes() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        s.debts.add(Debt.withoutMovement(UUID.fromString("00000000-0000-0000-0000-00000000db09"), USER_ID, PersonTestFactory.umPedro(),
                DebtDirection.PAYABLE, Money.of("75.00"), BASE_DATE, null, "sem data"));
        RecurringTransaction aluguel = RecurringTransaction.ofAccount(UUID.fromString("00000000-0000-0000-0000-00000000ec01"), USER_ID,
                TransactionType.EXPENSE, Money.of("1500.00"), "Aluguel", s.alimentacao.id(), s.nubank.id(), PaymentMethod.BOLETO,
                Schedule.of(Frequency.MONTHLY, d("2026-11-20")).limitedTo(3));
        LocalDate hoje = d("2026-11-10"); // fatura ref. nov (vence 05/11) vencida e não paga
        CommitmentSchedule agenda = CommitmentSchedule.of(s.records(), List.of(aluguel), hoje);

        assertThat(agenda.items().getFirst().bucket()).isEqualTo(Bucket.OVERDUE);
        assertThat(agenda.committed(Horizon.D7)).isEqualTo(Money.of("200.00"));
        assertThat(agenda.committed(Horizon.D30)).isEqualTo(Money.of("1900.00")); // 200 vencida + 1.500 (20/11) + 200 (05/12)
        assertThat(agenda.committed(Horizon.D60)).isEqualTo(Money.of("3600.00")); // + 1.500 (20/12) + 200 (05/01)
        assertThat(agenda.committed(Horizon.D90)).isEqualTo(Money.of("5300.00")); // + 1.500 (20/01) + 200 (05/02)
        assertThat(agenda.committed(Horizon.M6)).isEqualTo(Money.of("5700.00"));
        assertThat(agenda.committed(Horizon.M12)).isEqualTo(Money.of("5700.00"));
        assertThat(agenda.undated()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(CommitmentKind.DEBT_PAYABLE);
            assertThat(c.dueDate()).isNull();
        });
        assertThat(agenda.between(d("2026-11-20"), d("2026-11-20"))).singleElement()
                .satisfies(c -> assertThat(c.kind()).isEqualTo(CommitmentKind.RECURRENCE));
        assertThat(agenda.incoming(Horizon.M12)).isEqualTo(Money.of("0.00"));
    }

    @ParameterizedTest(name = "em {0}")
    @ValueSource(strings = {"2026-10-10", "2026-10-30"})
    @DisplayName("CA5: Nubank 20.000 e fatura ref. nov com 1.283 vencendo 05/11 → disponível 18.717 em 10/10 e em 30/10 (S-21, S-22)")
    void deveCalcularODisponivelSemSaltoNaVirada(LocalDate hoje) {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        s.compraAVista1083();

        DashboardSummary summary = DashboardSummary.of(s.records(), List.of(), hoje, hoje);

        assertThat(summary.balances().inAccounts()).isEqualTo(Money.of("20000.00"));
        assertThat(summary.balances().committed30d()).isEqualTo(Money.of("1283.00")).isEqualTo(summary.committed().get(Horizon.D30));
        assertThat(summary.balances().reserved()).isEqualTo(Money.of("0.00"));
        assertThat(summary.balances().available()).isEqualTo(Money.of("18717.00"));
        assertThat(summary.isProjection()).isFalse();
    }

    @Test
    @DisplayName("CA6: month.consumption/income/cashFlow iguais a EntryReport e CashFlowReport; month.net = receitas − consumo (S-23)")
    void deveUsarAsMesmasCalculadoras() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction pix = s.caso1DespesaPix();
        s.caso2CompraParcelada();
        s.caso9Reembolso(pix);
        s.caso4Transferencia();
        s.caso5Investimento();
        s.caso6Emprestimo();
        s.transactions.add(AccountTransactions.income(s.header(d("2026-10-05"), "Salário"), Money.of("5000.00"), s.nubank, s.salario, PaymentMethod.PIX));
        FinancialRecords records = s.records();
        Competence out = Competence.of(2026, 10);

        DashboardSummary summary = DashboardSummary.of(records, List.of(), BASE_DATE, BASE_DATE);

        assertThat(summary.month().consumption()).isEqualTo(EntryReport.of(records, records.ledger(), EntryLens.CONSUMPTION, out).total())
                .isEqualTo(Money.of("250.00"));
        assertThat(summary.month().income()).isEqualTo(EntryReport.of(records, records.ledger(), EntryLens.INCOME, out).total());
        assertThat(summary.cashFlow()).isEqualTo(CashFlowReport.forCompetence(records, records.ledger(), out));
        assertThat(summary.month().net()).isEqualTo(summary.month().income().subtract(summary.month().consumption()))
                .isEqualTo(Money.of("4750.00"));
        assertThat(summary.month().lent()).isEqualTo(Money.of("300.00"));
        assertThat(summary.month().investedNet()).isEqualTo(Money.of("10000.00"));
        assertThat(summary.balances().investments()).isEqualTo(Money.of("10000.00"));
        assertThat(summary.netWorth().cardFutureInstallments()).isEqualTo(Money.of("1000.00"));
        assertThat(summary.projected()).containsOnlyKeys(DashboardSummary.PROJECTION_HORIZONS);
        assertThat(summary.projected().get(Horizon.M12))
                .isEqualTo(summary.balances().inAccounts().add(Money.of("300.00")).subtract(Money.of("1200.00")));
        assertThat(DashboardSummary.of(records, List.of(), d("2026-12-01"), BASE_DATE).isProjection()).isTrue();
    }

    @Test
    @DisplayName("CA7: fatura CLOSED com saldo, em 06/11 → item INVOICE com bucket OVERDUE (mesma função de status de T6a)")
    void deveMarcarFaturaVencida() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        assertThat(agenda(s, d("2026-11-05")).items().getFirst().bucket()).isEqualTo(Bucket.TODAY);
        Commitment item = agenda(s, d("2026-11-06")).items().getFirst();
        assertThat(item.kind()).isEqualTo(CommitmentKind.INVOICE);
        assertThat(item.bucket()).isEqualTo(Bucket.OVERDUE);
        assertThat(s.chain().statusOf(NOV_CLOSING, d("2026-11-06")).name()).isEqualTo("OVERDUE");
    }

    @Test
    @DisplayName("buckets: hoje, amanhã, 7 dias, 30 dias e depois")
    void deveCalcularBuckets() {
        LocalDate hoje = d("2026-10-10");
        assertThat(Bucket.of(d("2026-10-09"), hoje)).isEqualTo(Bucket.OVERDUE);
        assertThat(Bucket.of(hoje, hoje)).isEqualTo(Bucket.TODAY);
        assertThat(Bucket.of(d("2026-10-11"), hoje)).isEqualTo(Bucket.TOMORROW);
        assertThat(Bucket.of(d("2026-10-17"), hoje)).isEqualTo(Bucket.NEXT_7_DAYS);
        assertThat(Bucket.of(d("2026-11-09"), hoje)).isEqualTo(Bucket.NEXT_30_DAYS);
        assertThat(Bucket.of(d("2026-11-10"), hoje)).isEqualTo(Bucket.LATER);
    }
}
