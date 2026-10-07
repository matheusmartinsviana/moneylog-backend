package com.moneylog.core.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.domain.CashFlowReport;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.finance.util.ListScenario;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionType;

/** T28 CA8: {@code between} com predicado, {@code forAccount} e {@code EntryReport.of} com predicado (S-79). */
@DisplayName("Fluxo de caixa e lançamentos com filtro (T28 CA8, S-79)")
class CashFlowEEntriesComFiltroTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);

    private final ListScenario s = new ListScenario();
    private final FinancialRecords records = s.records();
    private final Ledger ledger = records.ledger();

    @Test
    @DisplayName("between sem predicado dá o mesmo resultado de antes; com predicado aceitando tudo ou null também")
    void deveManterOComportamentoSemPredicado() {
        CashFlowReport semFiltro = CashFlowReport.between(records, ledger, FROM, TO);

        assertThat(semFiltro.in()).isEqualTo(Money.of("3050.00"));
        assertThat(semFiltro.out()).isEqualTo(Money.of("640.00"));
        assertThat(semFiltro.net()).isEqualTo(Money.of("2410.00"));
        assertThat(CashFlowReport.between(records, ledger, FROM, TO, t -> true)).isEqualTo(semFiltro);
        assertThat(CashFlowReport.between(records, ledger, FROM, TO, null)).isEqualTo(semFiltro);
    }

    @Test
    @DisplayName("between com predicado conta só as movimentações aceitas")
    void deveFiltrarPorPredicado() {
        CashFlowReport despesas = CashFlowReport.between(records, ledger, FROM, TO, t -> t.type() == TransactionType.EXPENSE);
        CashFlowReport pix = CashFlowReport.between(records, ledger, FROM, TO, t -> t.paymentMethod() == PaymentMethod.PIX);
        CashFlowReport nenhuma = CashFlowReport.between(records, ledger, FROM, TO, t -> false);

        assertThat(despesas.out()).isEqualTo(Money.of("140.00"));
        assertThat(despesas.in()).isEqualTo(Money.of("0.00"));
        assertThat(despesas.count()).isEqualTo(2);
        assertThat(pix.out()).isEqualTo(Money.of("100.00"));
        assertThat(pix.count()).isEqualTo(1);
        assertThat(nenhuma.count()).isZero();
        assertThat(nenhuma.net()).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("a estornada e a de outro mês nunca entram; o saldo inicial não é movimentação")
    void naoDeveContarEstornadaNemSaldoInicial() {
        CashFlowReport tudo = CashFlowReport.between(records, ledger, LocalDate.MIN, LocalDate.MAX);

        // 3.050 (receita + reembolso) entrada; 640 + 70 (novembro) saída; a estornada de 30 e os 20.000 iniciais ficam fora
        assertThat(tudo.in()).isEqualTo(Money.of("3050.00"));
        assertThat(tudo.out()).isEqualTo(Money.of("710.00"));
        assertThat(CashFlowReport.between(records, ledger, FROM, TO, t -> t.id().equals(s.estornada.id())).count()).isZero();
    }

    @Test
    @DisplayName("forAccount numa conta CASH: a transferência de saída é out na origem e in no destino")
    void deveMostrarATransferenciaPorConta() {
        CashFlowReport nubank = CashFlowReport.forAccount(records, ledger, s.nubank.id(), FROM, TO, null);
        CashFlowReport xp = CashFlowReport.forAccount(records, ledger, s.xp.id(), FROM, TO, null);

        assertThat(nubank.in()).isEqualTo(Money.of("3050.00"));
        assertThat(nubank.out()).isEqualTo(Money.of("1640.00"));
        assertThat(nubank.count()).isEqualTo(6);
        assertThat(xp.in()).isEqualTo(Money.of("1000.00"));
        assertThat(xp.out()).isEqualTo(Money.of("0.00"));
        assertThat(xp.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("forAccount numa conta INVESTMENT: a aplicação aparece como entrada; com predicado, só as aceitas")
    void deveMostrarAAplicacaoNaContaDeInvestimento() {
        CashFlowReport invest = CashFlowReport.forAccount(records, ledger, s.xpInvest.id(), FROM, TO, null);

        assertThat(invest.in()).isEqualTo(Money.of("500.00"));
        assertThat(invest.out()).isEqualTo(Money.of("0.00"));
        assertThat(CashFlowReport.forAccount(records, ledger, s.nubank.id(), FROM, TO, t -> t.type() == TransactionType.TRANSFER).out())
                .isEqualTo(Money.of("1000.00"));
        assertThat(CashFlowReport.forAccount(records, ledger, UUID.randomUUID(), FROM, TO, null).count()).isZero();
    }

    @Test
    @DisplayName("EntryReport com predicado: total e byCategory do filtro; sem predicado é igual ao de antes")
    void deveFiltrarOsLancamentos() {
        Competence outubro = Competence.of(2026, 10);
        EntryReport todos = EntryReport.of(records, ledger, EntryLens.CONSUMPTION, outubro);
        EntryReport alimentacao = EntryReport.of(records, ledger, EntryLens.CONSUMPTION, outubro, t -> s.alimentacao.id().equals(t.categoryId()));
        EntryReport pix = EntryReport.of(records, ledger, EntryLens.CONSUMPTION, outubro, t -> t.paymentMethod() == PaymentMethod.PIX);

        assertThat(todos.lines()).hasSize(3);
        assertThat(todos.total()).isEqualTo(Money.of("90.00"));
        assertThat(EntryReport.of(records, ledger, EntryLens.CONSUMPTION, outubro, null).total()).isEqualTo(todos.total());
        assertThat(alimentacao.total()).isEqualTo(Money.of("50.00"));
        assertThat(alimentacao.byCategory()).containsOnlyKeys(s.alimentacao.id());
        assertThat(pix.total()).isEqualTo(Money.of("100.00"));
        assertThat(pix.lines()).hasSize(1);
        assertThat(EntryReport.of(records, ledger, EntryLens.INCOME, outubro).total()).isEqualTo(Money.of("3000.00"));
    }
}
