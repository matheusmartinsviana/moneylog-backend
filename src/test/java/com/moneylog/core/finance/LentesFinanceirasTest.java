package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.finance.domain.CashPosition;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.finance.domain.NetWorth;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.AdjustmentDirection;
import com.moneylog.core.transaction.domain.AdjustmentKind;
import com.moneylog.core.transaction.domain.CardTransactionVoidPolicy;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.domain.TransactionVoidPolicy;

@DisplayName("Razão e lentes: datas, estorno, origens e patrimônio (T8a CA3–CA6)")
class LentesFinanceirasTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    @DisplayName("CA3: movimentação datada de 20/10 não entra no caixa de 10/10; em 20/10, entra")
    void naoDeveContarMovimentacaoFutura() {
        FinanceScenarioFactory s = umCenarioBase();
        s.transactions.add(AccountTransactions.expense(s.header(d("2026-10-20"), "futura"), Money.of("100.00"), s.nubank, s.alimentacao, PaymentMethod.PIX));
        FinancialRecords r = s.records();

        assertThat(CashPosition.at(r, r.ledger(), d("2026-10-10")).balanceOf(s.nubank.id())).isEqualTo(Money.of("20000.00"));
        assertThat(CashPosition.at(r, r.ledger(), d("2026-10-20")).balanceOf(s.nubank.id())).isEqualTo(Money.of("19900.00"));
        assertThat(CashPosition.at(r, r.ledger(), d("2026-09-30")).balanceOf(s.nubank.id())).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("CA4: movimentação estornada não aparece em nenhuma lente, inclusive em competências passadas")
    void deveIgnorarEstornadas() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction despesa = s.caso1DespesaPix();
        Transaction compra = s.caso2CompraParcelada();
        s.estornar(despesa, "duplicada", d("2026-10-11"), NOW);
        s.estornar(compra, "desisti", d("2026-10-11"), NOW);
        FinancialRecords r = s.records();
        Ledger ledger = r.ledger();

        assertThat(CashPosition.at(r, ledger, d("2027-12-31")).balanceOf(s.nubank.id())).isEqualTo(Money.of("20000.00"));
        for (int i = 0; i < 6; i++) {
            assertThat(ConsumptionReport.of(r, ledger, Competence.of(2026, 10).plusMonths(i)).consumption()).isEqualTo(Money.of("0.00"));
        }
        assertThat(NetWorth.at(ledger, d("2027-12-31")).total()).isEqualTo(Money.of("20000.00"));
        assertThat(ledger.entries()).noneMatch(e -> e.source().type() == PostingSourceType.TRANSACTION);
    }

    @Test
    @DisplayName("CA4: saldo inicial, dívida sem movimentação e perdão entram pelas suas origens, sem Transaction")
    void deveLerTodasAsOrigens() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso8DividaSemMovimentacao().cancel("perdoada", d("2026-10-15"));
        Ledger ledger = s.records().ledger();

        assertThat(ledger.entries()).extracting(e -> e.source().type())
                .contains(PostingSourceType.ACCOUNT_OPENING, PostingSourceType.DEBT_RECOGNITION, PostingSourceType.DEBT_CANCELLATION)
                .doesNotContain(PostingSourceType.TRANSACTION);
        assertThat(NetWorth.at(ledger, d("2026-10-10")).total()).isEqualTo(Money.of("19800.00"));
        assertThat(NetWorth.at(ledger, d("2026-10-15")).total()).isEqualTo(Money.of("20000.00"));
        assertThat(NetWorth.at(ledger, d("2026-10-15")).payables()).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("CA4: saldo inicial corrigido conta só o par ativo")
    void deveContarSoOParAtivoDoSaldoInicial() {
        FinanceScenarioFactory s = umCenarioBase();
        List<LedgerEntry> entries = new ArrayList<>(s.records().entries().stream().map(LedgerEntry::deactivate).toList());
        entries.addAll(LedgerEntry.activeGroup(PostingSource.accountOpening(s.nubank.id()), List.of(
                new Posting(BookRef.account(s.nubank.id()), Money.of("18500.00"), d("2026-10-01")),
                new Posting(BookRef.equity(), Money.of("-18500.00"), d("2026-10-01")))));
        Ledger ledger = new Ledger(entries);

        assertThat(ledger.balance(BookRef.account(s.nubank.id()), d("2026-10-10"))).isEqualTo(Money.of("18500.00"));
    }

    @ParameterizedTest(name = "até {0}")
    @ValueSource(strings = {"2026-09-30", "2026-10-01", "2026-10-10", "2026-10-15", "2026-11-05", "2027-03-31", "2030-01-01"})
    @DisplayName("CA5: com todos os tipos e todas as origens, Σ postings ativos até qualquer data = 0, no total e por origem")
    void deveFecharORazao(LocalDate ate) {
        FinanceScenarioFactory s = cenarioCompleto();
        Ledger ledger = s.records().ledger();

        assertThat(ledger.sum(e -> !e.posting().effectiveDate().isAfter(ate))).isEqualTo(Money.of("0.00"));
        Map<PostingSource, Money> porOrigem = ledger.entries().stream()
                .filter(e -> !e.posting().effectiveDate().isAfter(ate))
                .collect(Collectors.groupingBy(LedgerEntry::source, Collectors.reducing(Money.zero(CurrencyCode.BRL),
                        e -> e.posting().amount(), Money::add)));
        assertThat(porOrigem.values()).allMatch(Money::isZero);
        assertThat(s.transactions.stream().map(Transaction::type).distinct()).hasSize(TransactionType.values().length);
        assertThat(ledger.entries().stream().map(e -> e.source().type()).distinct()).containsExactlyInAnyOrder(PostingSourceType.TRANSACTION,
                PostingSourceType.ACCOUNT_OPENING, PostingSourceType.DEBT_RECOGNITION, PostingSourceType.DEBT_CANCELLATION);
    }

    @Test
    @DisplayName("CA6: no caso 2 em 10/10, cardFutureInstallments = 1.000 e fora do total (S-8); com crédito no cartão, cardLiabilities > 0")
    void deveInformarParcelasFuturas() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        NetWorth nw = NetWorth.at(s.records().ledger(), d("2026-10-10"));
        assertThat(nw.cardFutureInstallments()).isEqualTo(Money.of("1000.00"));
        assertThat(nw.cardLiabilities()).isEqualTo(Money.of("-200.00"));
        assertThat(nw.total()).isEqualTo(Money.of("19800.00"));
        assertThat(nw.total()).isEqualTo(nw.accounts().add(nw.receivables()).add(nw.cardLiabilities()).add(nw.payables()));

        s.pagarFatura("500.00", d("2026-10-25"), d("2026-10-20"));
        assertThat(NetWorth.at(s.records().ledger(), d("2026-10-20")).cardLiabilities()).isEqualTo(Money.of("300.00"));
    }

    static FinanceScenarioFactory cenarioCompleto() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction despesa = s.caso1DespesaPix();
        s.caso2CompraParcelada();
        s.compraAVista1083();
        s.caso4Transferencia();
        s.caso5Investimento();
        s.caso6Emprestimo();
        s.caso7Devolucao();
        s.caso8DividaSemMovimentacao();
        s.caso9Reembolso(despesa);
        s.transactions.add(AccountTransactions.income(s.header(d("2026-10-05"), "Salário"), Money.of("5000.00"), s.nubank, s.salario, PaymentMethod.TRANSFER));
        s.transactions.add(AccountTransactions.adjustment(s.header(d("2026-10-31"), "Rendimento"), Money.of("12.34"), s.xpInvest,
                AdjustmentDirection.INCREASE, AdjustmentKind.YIELD, "rendimento"));
        s.transactions.add(com.moneylog.core.transaction.domain.DebtTransactions.debtPayment(s.header(d("2026-10-20"), "Pedro"),
                Money.of("50.00"), s.nubank, s.debts.get(1)).transaction());
        s.debts.get(1).cancel("perdão", d("2026-10-21"));
        s.caso3PagarFatura("1283.00");
        s.transactions.add(com.moneylog.core.transaction.domain.CardTransactions.refundToCard(s.header(d("2026-11-10"), "Estorno"),
                Money.of("20.00"), s.roxinho, s.alimentacao, null, s.chain(), d("2026-11-10")));
        Transaction estornada = AccountTransactions.expense(s.header(d("2026-10-11"), "x"), Money.of("1.00"), s.nubank, s.alimentacao, PaymentMethod.CASH);
        s.estornar(estornada, "teste", d("2026-10-11"), NOW);
        s.transactions.add(estornada);
        return s;
    }

}
