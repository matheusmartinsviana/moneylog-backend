package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.moneylog.core.util.Ids.USER_ID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.DashboardSummary.InAccountsChange;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.MonthlySummary;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;

@DisplayName("Variações do Início (T187, T188): taxa de poupança do mês anterior e variação do saldo em contas")
class VariacoesDoInicioTest {

    private static DashboardSummary dashboard(FinancialRecords records, LocalDate asOf, LocalDate today) {
        return DashboardSummary.of(records, List.of(), asOf, today);
    }

    private static MonthlySummary summary(FinancialRecords records, String month, LocalDate today) {
        return MonthlySummary.of(records, records.ledger(), Competence.parse(month), today);
    }

    private FinanceScenarioFactory poupanca(String incomeNow, String spendNow, String incomePrev, String spendPrev) {
        FinanceScenarioFactory s = umCenarioBase();
        if (incomeNow != null) {
            s.transactions.add(AccountTransactions.income(s.header(LocalDate.of(2026, 10, 5), "Salário"), Money.of(incomeNow), s.nubank, s.salario,
                    PaymentMethod.PIX));
        }
        s.transactions.add(AccountTransactions.expense(s.header(LocalDate.of(2026, 10, 6), "Gasto"), Money.of(spendNow), s.nubank, s.alimentacao,
                PaymentMethod.PIX));
        if (incomePrev != null) {
            s.transactions.add(AccountTransactions.income(s.header(LocalDate.of(2026, 9, 5), "Salário set"), Money.of(incomePrev), s.nubank,
                    s.salario, PaymentMethod.PIX));
        }
        s.transactions.add(AccountTransactions.expense(s.header(LocalDate.of(2026, 9, 6), "Gasto set"), Money.of(spendPrev), s.nubank,
                s.alimentacao, PaymentMethod.PIX));
        return s;
    }

    @Test
    @DisplayName("T187: receita 3000 e despesa 250 contra 3000 e 150 → savingsRate 0.9167, anterior 0.9500, delta −0.0333 (sobre as taxas arredondadas)")
    void deveCalcularODeltaDaTaxaDePoupanca() {
        FinancialRecords records = poupanca("3000.00", "250.00", "3000.00", "150.00").records();

        MonthlySummary s = summary(records, "2026-10", BASE_DATE);

        assertThat(s.savingsRate().toPlainString()).isEqualTo("0.9167");
        assertThat(s.comparison().previousTotals().savingsRate().toPlainString()).isEqualTo("0.9500");
        assertThat(s.comparison().savingsRateDelta().toPlainString()).isEqualTo("-0.0333");
    }

    @Test
    @DisplayName("T187: mês anterior sem receita (ou o mês sem receita) → os dois campos novos são null")
    void deveDevolverNuloSemReceita() {
        MonthlySummary noPrevious = summary(poupanca("3000.00", "250.00", null, "150.00").records(), "2026-10", BASE_DATE);
        assertThat(noPrevious.comparison().previousTotals().savingsRate()).isNull();
        assertThat(noPrevious.comparison().savingsRateDelta()).isNull();

        MonthlySummary noCurrent = summary(poupanca(null, "250.00", "3000.00", "150.00").records(), "2026-10", BASE_DATE);
        assertThat(noCurrent.savingsRate()).isNull();
        assertThat(noCurrent.comparison().previousTotals().savingsRate().toPlainString()).isEqualTo("0.9500");
        assertThat(noCurrent.comparison().savingsRateDelta()).isNull();
    }

    @Test
    @DisplayName("T187: taxa negativa (gastou mais do que ganhou) entra no delta com sinal; os campos existentes não mudam")
    void deveAceitarTaxaNegativa() {
        MonthlySummary s = summary(poupanca("1000.00", "1500.00", "1000.00", "500.00"), "2026-10");

        assertThat(s.savingsRate().toPlainString()).isEqualTo("-0.5000");
        assertThat(s.comparison().previousTotals().savingsRate().toPlainString()).isEqualTo("0.5000");
        assertThat(s.comparison().savingsRateDelta().toPlainString()).isEqualTo("-1.0000");
        assertThat(s.comparison().expenses().amount()).isEqualTo(Money.of("1000.00"));
        assertThat(s.comparison().previousTotals().net()).isEqualTo(Money.of("500.00"));
    }

    private static MonthlySummary summary(FinanceScenarioFactory s, String month) {
        return summary(s.records(), month, BASE_DATE);
    }

    private static final UUID CONTA_ID = UUID.fromString("00000000-0000-0000-0000-00000000ac01");

    private static Account contaDeSetembro() {
        return Account.open(CONTA_ID, USER_ID, "Conta de setembro", AccountType.CHECKING, null, Money.of("1000.00"), LocalDate.of(2026, 9, 1));
    }

    private static Transaction receita(FinanceScenarioFactory s, Account account, String amount, LocalDate date) {
        return AccountTransactions.income(s.header(date, "Receita"), Money.of(amount), account, s.salario, PaymentMethod.PIX);
    }

    @Test
    @DisplayName("T188: previous = inAccounts no último dia do mês anterior (exato por data), amount = asOf − previous, percent com 4 casas")
    void deveCalcularAVariacaoDoSaldoEmContas() {
        FinanceScenarioFactory s = umCenarioBase();
        Account conta = contaDeSetembro();
        FinancialRecords records = new FinancialRecords(List.of(conta), List.of(), List.of(receita(s, conta, "84.00", LocalDate.of(2026, 10, 5))),
                List.of());

        InAccountsChange change = dashboard(records, BASE_DATE, BASE_DATE).balances().inAccountsChange();

        assertThat(change.since()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(change.previous()).isEqualTo(Money.of("1000.00"));
        assertThat(change.amount()).isEqualTo(Money.of("84.00"));
        assertThat(change.percent().toPlainString()).isEqualTo("0.0840");
        assertThat(dashboard(records, LocalDate.of(2026, 9, 30), BASE_DATE).balances().inAccounts())
                .as("previous é o inAccounts de um dashboard pedido em since").isEqualTo(change.previous());
    }

    @Test
    @DisplayName("T188: previous = 0 → percent null; previous negativo → percent usa |previous| e o sinal de amount manda")
    void deveTratarBaseZeroENegativa() {
        FinanceScenarioFactory s = umCenarioBase();
        FinancialRecords fromZero = s.records();
        InAccountsChange zero = dashboard(fromZero, BASE_DATE, BASE_DATE).balances().inAccountsChange();
        assertThat(zero.previous().isZero()).isTrue();
        assertThat(zero.amount()).isEqualTo(Money.of("20000.00"));
        assertThat(zero.percent()).isNull();

        Account overdrawn = Account.open(CONTA_ID, USER_ID, "Cheque especial", AccountType.CHECKING, null, Money.of("-200.00"), LocalDate.of(2026, 9, 1));
        FinancialRecords negative = new FinancialRecords(List.of(overdrawn), List.of(), List.of(receita(s, overdrawn, "50.00", LocalDate.of(2026, 10, 5))),
                List.of());
        InAccountsChange change = dashboard(negative, BASE_DATE, BASE_DATE).balances().inAccountsChange();
        assertThat(change.previous()).isEqualTo(Money.of("-200.00"));
        assertThat(change.amount()).isEqualTo(Money.of("50.00"));
        assertThat(change.percent().toPlainString()).isEqualTo("0.2500");
    }

    @Test
    @DisplayName("T188: aplicação de 10.000 neste mês reduz amount (campo de contas, não de patrimônio) e investments segue em balances")
    void deveReduzirComAAplicacao() {
        FinanceScenarioFactory s = umCenarioBase();
        Account conta = contaDeSetembro();
        Account invest = Account.open(UUID.fromString("00000000-0000-0000-0000-00000000ac02"), USER_ID, "Invest", AccountType.INVESTMENT, null,
                Money.of("0.00"), LocalDate.of(2026, 9, 1));
        FinancialRecords records = new FinancialRecords(List.of(conta, invest), List.of(), List.of(AccountTransactions.investment(
                s.header(LocalDate.of(2026, 10, 5), "CDB"), Money.of("400.00"), InvestmentDirection.CONTRIBUTION, conta, invest)), List.of());

        DashboardSummary summary = dashboard(records, BASE_DATE, BASE_DATE);

        assertThat(summary.balances().inAccountsChange().amount()).isEqualTo(Money.of("-400.00"));
        assertThat(summary.balances().inAccountsChange().percent().toPlainString()).isEqualTo("-0.4000");
        assertThat(summary.balances().investments()).isEqualTo(Money.of("400.00"));
    }

    @Test
    @DisplayName("T188: movimentação datada depois de asOf não entra; asOf futuro (projeção) e usuário sem contas omitem o campo")
    void deveOmitirEmProjecaoESemContas() {
        FinanceScenarioFactory s = umCenarioBase();
        Account conta = contaDeSetembro();
        FinancialRecords records = new FinancialRecords(List.of(conta), List.of(), List.of(receita(s, conta, "500.00", LocalDate.of(2026, 10, 20))),
                List.of());

        assertThat(dashboard(records, BASE_DATE, BASE_DATE).balances().inAccountsChange().amount().isZero()).as("a receita é futura").isTrue();
        assertThat(dashboard(records, LocalDate.of(2026, 11, 1), BASE_DATE).balances().inAccountsChange()).as("projeção").isNull();
        FinancialRecords none = new FinancialRecords(List.of(), List.of(), List.of(), List.of());
        assertThat(dashboard(none, BASE_DATE, BASE_DATE).balances().inAccountsChange()).as("sem contas").isNull();
    }
}
