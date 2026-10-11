package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.domain.CashFlowReport;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;

@DisplayName("Lista × dashboard: visões de leitura consistentes (T8a CA7)")
class ListaDashboardConsistenciaTest {

    private static final Competence OUT = Competence.of(2026, 10);

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    @DisplayName("despesas pagas + crédito − reembolsos = Σ EXPENSE = EntryReport(CONSUMPTION).total; receita idem; fluxo de caixa sem transferência nem saldo inicial")
    void deveBaterListaEDashboard() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction pix = s.caso1DespesaPix();
        s.transactions.add(AccountTransactions.expense(s.header(d("2026-10-11"), "Farmácia"), Money.of("40.00"), s.nubank, s.alimentacao, PaymentMethod.DEBIT));
        s.transactions.add(AccountTransactions.expense(s.header(d("2026-10-12"), "Luz"), Money.of("60.00"), s.nubank, s.alimentacao, PaymentMethod.BOLETO));
        Transaction compra = s.caso2CompraParcelada();
        s.transactions.add(AccountTransactions.income(s.header(d("2026-10-05"), "Salário"), Money.of("5000.00"), s.nubank, s.salario, PaymentMethod.TRANSFER));
        s.transactions.add(AccountTransactions.refundToAccount(s.header(d("2026-10-13"), "Estorno conta"), Money.of("30.00"), s.nubank, s.alimentacao, null));
        s.transactions.add(CardTransactions.refundToCard(s.header(d("2026-10-14"), "Estorno cartão"), Money.of("20.00"), s.roxinho, s.alimentacao, null,
                s.chain(), d("2026-10-14")));
        s.caso4Transferencia();
        s.transactions.add(AccountTransactions.investment(s.header(d("2026-10-15"), "CDB"), Money.of("2000.00"), InvestmentDirection.CONTRIBUTION, s.nubank, s.xpInvest));
        FinancialRecords records = s.records();
        Ledger ledger = records.ledger();

        ConsumptionReport month = ConsumptionReport.of(records, ledger, OUT);
        EntryReport consumo = EntryReport.of(records, ledger, EntryLens.CONSUMPTION, OUT);
        EntryReport receita = EntryReport.of(records, ledger, EntryLens.INCOME, OUT);
        CashFlowReport caixa = CashFlowReport.forCompetence(records, ledger, OUT);

        assertThat(month.expensesPaid()).isEqualTo(Money.of("200.00"));
        assertThat(month.expensesByMethod().get(PaymentMethod.PIX)).isEqualTo(Money.of("100.00"));
        assertThat(month.expensesByMethod().get(PaymentMethod.DEBIT)).isEqualTo(Money.of("40.00"));
        assertThat(month.expensesByMethod().get(PaymentMethod.BOLETO)).isEqualTo(Money.of("60.00"));
        assertThat(month.expensesByMethod().get(PaymentMethod.CASH)).isEqualTo(Money.of("0.00"));
        assertThat(month.credit()).isEqualTo(Money.of("200.00"));
        assertThat(month.refunds()).isEqualTo(Money.of("50.00"));
        Money identidade = month.expensesPaid().add(month.credit()).subtract(month.refunds());
        assertThat(identidade).isEqualTo(month.consumption()).isEqualTo(Money.of("350.00"));
        assertThat(ledger.sum(e -> e.posting().bookType() == BookType.EXPENSE && e.posting().competence().equals(OUT))).isEqualTo(identidade);
        assertThat(consumo.total()).isEqualTo(month.consumption());
        assertThat(consumo.byCategory().get(s.alimentacao.id())).isEqualTo(Money.of("350.00"));
        assertThat(receita.total()).isEqualTo(month.income()).isEqualTo(Money.of("5000.00"));
        assertThat(month.net()).isEqualTo(Money.of("4650.00"));
        assertThat(month.investedNet()).isEqualTo(Money.of("2000.00"));

        assertThat(caixa.in()).isEqualTo(Money.of("5030.00"));
        assertThat(caixa.out()).isEqualTo(Money.of("2200.00"));
        assertThat(caixa.net()).isEqualTo(Money.of("2830.00"));
        assertThat(caixa.count()).isEqualTo(6);

        assertThat(consumo.lines()).filteredOn(l -> compra.id().equals(l.transactionId())).singleElement().satisfies(l -> {
            assertThat(l.amount()).isEqualTo(Money.of("200.00"));
            assertThat(l.installmentNumber()).isEqualTo(1);
            assertThat(l.installmentTotal()).isEqualTo(6);
            assertThat(l.description()).isEqualTo("Notebook");
        });
        assertThat(consumo.lines()).filteredOn(l -> l.amount().isNegative()).extracting(EntryReport.Line::amount)
                .containsExactlyInAnyOrder(Money.of("-30.00"), Money.of("-20.00"));
        assertThat(consumo.lines()).filteredOn(l -> pix.id().equals(l.transactionId())).singleElement()
                .satisfies(l -> assertThat(l.installmentNumber()).isNull());
        assertThat(receita.lines()).singleElement().satisfies(l -> assertThat(l.amount()).isEqualTo(Money.of("5000.00")));
    }
}
