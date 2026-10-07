package com.moneylog.core.card.domain;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.domain.NetWorth;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.CardTransactionVoidPolicy;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundExceedsOriginalException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionVoidRefusedException;

/**
 * T12 (QA): estornos e guardas de cartão nas bordas do relógio (D1 §2.4 regra 1, S-36) e a correção recomendada
 * (cancelar parcelas restantes + reembolso) fechando sem dupla contagem.
 */
@DisplayName("Estornos e guardas do cartão (T12, QA)")
class EstornosEGuardasQaTest {

    private static final LocalDate CLOSING = LocalDate.of(2026, 10, 25);
    private static final Instant NOW = Instant.parse("2026-10-30T15:00:00Z");

    private static Money m(String v) {
        return Money.of(v);
    }

    @Test
    @DisplayName("compra é estornável na véspera do fechamento (24/10) e recusada no dia do fechamento (25/10)")
    void deveVirarAGuardaNoFechamento() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction compra = s.caso2CompraParcelada();
        assertThat(CardVoidAllowed(s, compra, CLOSING.minusDays(1))).isTrue();
        assertThat(CardVoidAllowed(s, compra, CLOSING)).isFalse();
        assertThatThrownBy(() -> s.estornar(compra, "erro", CLOSING, NOW))
                .isInstanceOf(TransactionVoidRefusedException.class);
        assertThat(compra.isActive()).isTrue();
    }

    private static boolean CardVoidAllowed(FinanceScenarioFactory s, Transaction t, LocalDate today) {
        return new CardTransactionVoidPolicy(s.roxinho, s.chain(), today).evaluate(t).allowed();
    }

    @Test
    @DisplayName("correção pós-fechamento: cancelar restantes + reembolso de 200 no ciclo da compra zera consumo e CARD")
    void deveCorrigirCompraFechadaSemDuplaContagem() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction compra = s.caso2CompraParcelada();
        LocalDate hoje = LocalDate.of(2026, 10, 27);
        CardTransactions.cancelRemainingInstallments(compra, s.chain(), hoje, NOW);
        assertThat(compra.installments().stream().filter(TransactionInstallment::isCanceled).count()).isEqualTo(5);
        Transaction reembolso = CardTransactions.refundToCard(s.header(LocalDate.of(2026, 10, 20), "estorno banco"), m("200.00"), s.roxinho,
                s.alimentacao, RefundOrigin.firstRefundOf(compra), s.chain(), hoje);
        s.transactions.add(reembolso);
        assertThat(reembolso.lateEntry()).isTrue();
        assertThat(reembolso.invoiceClosingDate()).isEqualTo(CLOSING);
        var ledger = s.records().ledger();
        assertThat(ledger.balanceAllDates(BookRef.card(s.roxinho.id()))).isEqualTo(m("0.00"));
        assertThat(ledger.balanceAllDates(BookRef.expense(s.alimentacao.id()))).isEqualTo(m("0.00"));
        assertThat(s.chain().invoice(CLOSING).total()).isEqualTo(m("0.00"));
        assertThat(s.chain().statusOf(CLOSING, hoje)).isEqualTo(InvoiceStatus.PAID);
        assertThat(NetWorth.at(ledger, hoje).cardFutureInstallments()).isEqualTo(m("0.00"));
    }

    @Test
    @DisplayName("reembolso maior que o original é recusado; reembolsos parciais somados até o total são aceitos")
    void deveLimitarReembolsoAoOriginal() {
        FinanceScenarioFactory s = umCenarioBase();
        Transaction compra = s.caso1DespesaPix();
        assertThat(s.caso9Reembolso(compra).amount()).isEqualTo(m("50.00"));
        assertThatThrownBy(() -> com.moneylog.core.transaction.domain.AccountTransactions.refundToAccount(
                s.header(LocalDate.of(2026, 10, 11), "r"), m("50.01"), s.nubank, s.alimentacao, new RefundOrigin(compra, m("50.00"))))
                .isInstanceOf(RefundExceedsOriginalException.class);
        assertThat(com.moneylog.core.transaction.domain.AccountTransactions.refundToAccount(s.header(LocalDate.of(2026, 10, 11), "r"),
                m("50.00"), s.nubank, s.alimentacao, new RefundOrigin(compra, m("50.00"))).amount()).isEqualTo(m("50.00"));
    }

    @Test
    @DisplayName("estorno do pagamento: fatura PAID volta para CLOSED antes do vencimento e OVERDUE depois; crédito transportado some")
    void deveRecalcularStatusAoEstornarPagamento() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        Transaction pagamento = s.pagarFatura("300.00", CLOSING, LocalDate.of(2026, 10, 30));
        assertThat(s.chain().statusOf(CLOSING, LocalDate.of(2026, 10, 30))).isEqualTo(InvoiceStatus.PAID);
        assertThat(s.chain().invoice(LocalDate.of(2026, 11, 25)).carryIn()).isEqualTo(m("100.00"));
        s.estornar(pagamento, "pix devolvido", LocalDate.of(2026, 10, 30), NOW);
        assertThat(s.chain().statusOf(CLOSING, LocalDate.of(2026, 11, 5))).isEqualTo(InvoiceStatus.CLOSED);
        assertThat(s.chain().statusOf(CLOSING, LocalDate.of(2026, 11, 6))).isEqualTo(InvoiceStatus.OVERDUE);
        assertThat(s.chain().invoice(LocalDate.of(2026, 11, 25)).carryIn()).isEqualTo(m("0.00"));
    }

    @Test
    @DisplayName("reembolso no cartão em fatura FUTURE é estornável; depois que ela fecha, não")
    void deveGuardarReembolsoNoCartao() {
        FinanceScenarioFactory s = umCenarioBase();
        s.caso2CompraParcelada();
        LocalDate hoje = LocalDate.of(2026, 10, 26);
        Transaction credito = CardTransactions.refundToCard(s.header(hoje, "c"), m("50.00"), s.roxinho, s.alimentacao, null, s.chain(), hoje);
        s.transactions.add(credito);
        assertThat(credito.invoiceClosingDate()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(CardVoidAllowed(s, credito, hoje)).isTrue();
        assertThat(CardVoidAllowed(s, credito, LocalDate.of(2026, 11, 25))).isFalse();
    }
}
