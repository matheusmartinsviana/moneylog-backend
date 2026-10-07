package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.EXPECTED;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.moneylog.core.card.domain.AvailableLimitCalculator;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.finance.domain.Commitment;
import com.moneylog.core.finance.domain.CommitmentKind;
import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * Coluna "Compromisso" dos 9 casos do PR 01 (D1 §3), com os números exatos.
 */
@DisplayName("Compromissos dos casos do PR 01 (T8b CA1)")
class CompromissosPR01Test {

    private static CommitmentSchedule agenda(FinanceScenarioFactory s, LocalDate hoje) {
        return CommitmentSchedule.of(s.records(), List.of(), hoje);
    }

    @Test
    @DisplayName("caso 2: +1.200 — 200 na previsão da fatura ref. nov/26 + 1.000 nas faturas dez/26..abr/27; limite disponível −1.200")
    void caso2() {
        FinanceScenarioFactory s = umCenarioBase();
        CommitmentSchedule antes = agenda(s, BASE_DATE);
        s.caso2CompraParcelada();
        CommitmentSchedule depois = agenda(s, BASE_DATE);

        assertThat(depois.totalOut().subtract(antes.totalOut())).isEqualTo(Money.of("1200.00"));
        List<Commitment> faturas = depois.items();
        assertThat(faturas).hasSize(6).allMatch(c -> c.kind() == CommitmentKind.INVOICE_FORECAST);
        assertThat(faturas.getFirst().dueDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(faturas.getFirst().amount()).isEqualTo(Money.of("200.00"));
        assertThat(Money.sum(faturas.subList(1, 6).stream().map(Commitment::amount).toList(), Money.of("0.00").currency()))
                .isEqualTo(Money.of("1000.00"));
        assertThat(faturas.getLast().dueDate()).isEqualTo(LocalDate.of(2027, 4, 5));
        assertThat(AvailableLimitCalculator.calculate(s.roxinho, s.records().ledger().entries().stream().map(e -> e.posting()).toList())
                .available()).isEqualTo(Money.of("8800.00"));
    }

    @Test
    @DisplayName("caso 3: pagar a fatura de 1.283 → compromisso −1.283; fatura PAID sai da agenda")
    void caso3() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        s.compraAVista1083();
        LocalDate hoje = LocalDate.of(2026, 11, 5);
        CommitmentSchedule antes = agenda(s, hoje);
        s.caso3PagarFatura("1283.00");
        CommitmentSchedule depois = agenda(s, hoje);

        assertThat(antes.items().getFirst().kind()).isEqualTo(CommitmentKind.INVOICE);
        assertThat(depois.totalOut().subtract(antes.totalOut())).isEqualTo(Money.of("-1283.00"));
        assertThat(depois.items()).noneMatch(c -> LocalDate.of(2026, 10, 25).equals(c.refDate()));
    }

    @Test
    @DisplayName("caso 6: emprestar 300 → entrada prevista +300 na expectedDate")
    void caso6() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso6Emprestimo();
        CommitmentSchedule depois = agenda(s, BASE_DATE);

        assertThat(depois.totalIn()).isEqualTo(Money.of("300.00"));
        assertThat(depois.items()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(CommitmentKind.DEBT_RECEIVABLE);
            assertThat(c.direction()).isEqualTo(FlowDirection.IN);
            assertThat(c.dueDate()).isEqualTo(EXPECTED);
        });
        assertThat(depois.totalOut()).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("caso 7: João devolve 300 → entrada prevista removida; dívida SETTLED")
    void caso7() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso6Emprestimo();
        s.caso7Devolucao();
        CommitmentSchedule depois = agenda(s, BASE_DATE);

        assertThat(depois.totalIn()).isEqualTo(Money.of("0.00"));
        assertThat(depois.items()).isEmpty();
        assertThat(s.debts.getFirst().status()).isEqualTo(DebtStatus.SETTLED);
    }

    @Test
    @DisplayName("caso 8: eu devo 200 sem movimentação → saída prevista 200 na expectedDate")
    void caso8() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso8DividaSemMovimentacao();
        CommitmentSchedule depois = agenda(s, BASE_DATE);

        assertThat(depois.items()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(CommitmentKind.DEBT_PAYABLE);
            assertThat(c.amount()).isEqualTo(Money.of("200.00"));
            assertThat(c.dueDate()).isEqualTo(EXPECTED);
        });
    }

    static Stream<Arguments> semCompromisso() {
        return Stream.of(
                Arguments.of("caso 1", (Consumer<FinanceScenarioFactory>) FinanceScenarioFactory::caso1DespesaPix),
                Arguments.of("caso 4", (Consumer<FinanceScenarioFactory>) FinanceScenarioFactory::caso4Transferencia),
                Arguments.of("caso 5", (Consumer<FinanceScenarioFactory>) FinanceScenarioFactory::caso5Investimento),
                Arguments.of("caso 9", (Consumer<FinanceScenarioFactory>) s -> {
                    Transaction original = s.caso1DespesaPix();
                    s.caso9Reembolso(original);
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("semCompromisso")
    @DisplayName("casos 1, 4, 5 e 9: compromisso sem mudança")
    void naoDeveMudarCompromisso(String caso, Consumer<FinanceScenarioFactory> operacao) {
        FinanceScenarioFactory s = umCenarioBase();
        operacao.accept(s);
        CommitmentSchedule agenda = agenda(s, BASE_DATE);
        assertThat(agenda.items()).isEmpty();
        assertThat(agenda.undated()).isEmpty();
    }
}
