package com.moneylog.core.transaction.domain;

import static com.moneylog.core.card.util.factory.InvoiceScenario.NOW;
import static com.moneylog.core.card.util.factory.InvoiceScenario.PURCHASE_6X_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.util.factory.InvoiceScenario;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundExceedsOriginalException;

/**
 * A1 (S-9): o teto do reembolso com {@code refundOf} é o valor efetivamente cobrado (Σ parcelas não canceladas, na
 * compra no cartão) menos os reembolsos anteriores. Sem isso, cancelar as parcelas restantes + reembolsar o total
 * revertia o consumo duas vezes.
 */
@DisplayName("Reembolso depois de cancelar parcelas (A1, S-9)")
class ReembolsoComParcelasCanceladasTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 26); // fatura de 25/10 fechada: só a parcela 1 foi cobrada

    private static TransactionHeader header(int n) {
        return TransactionHeader.of(UUID.fromString(String.format("00000000-0000-0000-0000-0000000a1%03d", n)), USER_ID, HOJE, "Reembolso");
    }

    private static Money consumo(InvoiceScenario s) {
        return Money.sum(s.transactions().stream().flatMap(t -> t.postings().stream())
                .filter(p -> p.bookType() == BookType.EXPENSE).map(Posting::amount).toList(), CurrencyCode.BRL);
    }

    private static InvoiceScenario compraComRestantesCanceladas() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        CardTransactions.cancelRemainingInstallments(s.get(PURCHASE_6X_ID), s.chain(), HOJE, NOW);
        return s;
    }

    @Test
    @DisplayName("cancelar as 5 restantes + reembolso de 1.200 (ou 200,01) é recusado; o teto é o cobrado (200)")
    void deveLimitarAoValorCobrado() {
        InvoiceScenario s = compraComRestantesCanceladas();
        Transaction compra = s.get(PURCHASE_6X_ID);
        assertThat(compra.chargedAmount()).isEqualTo(Money.of("200.00"));
        RefundOrigin origem = RefundOrigin.firstRefundOf(compra);

        for (String valor : new String[] {"1200.00", "200.01"}) {
            assertThatThrownBy(() -> CardTransactions.refundToCard(header(1), Money.of(valor), s.card(),
                    CategoryTestFactory.umaCategoriaDeDespesa(), origem, s.chain(), HOJE))
                    .as(valor).isInstanceOf(RefundExceedsOriginalException.class);
            assertThatThrownBy(() -> AccountTransactions.refundToAccount(header(2), Money.of(valor), AccountTestFactory.umaContaCorrente(),
                    CategoryTestFactory.umaCategoriaDeDespesa(), origem))
                    .as(valor).isInstanceOf(RefundExceedsOriginalException.class);
        }

        s.transactions().add(CardTransactions.refundToCard(header(3), Money.of("200.00"), s.card(),
                CategoryTestFactory.umaCategoriaDeDespesa(), origem, s.chain(), HOJE));
        assertThat(consumo(s)).as("consumo líquido da compra: 200 cobrado − 200 reembolsado, nunca negativo").isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("os reembolsos anteriores contam: 150 já reembolsado deixa só 50 de teto")
    void deveDescontarReembolsosAnteriores() {
        InvoiceScenario s = compraComRestantesCanceladas();
        RefundOrigin jaReembolsado = new RefundOrigin(s.get(PURCHASE_6X_ID), Money.of("150.00"));
        assertThatThrownBy(() -> CardTransactions.refundToCard(header(4), Money.of("50.01"), s.card(),
                CategoryTestFactory.umaCategoriaDeDespesa(), jaReembolsado, s.chain(), HOJE))
                .isInstanceOf(RefundExceedsOriginalException.class);
        assertThat(CardTransactions.refundToCard(header(5), Money.of("50.00"), s.card(), CategoryTestFactory.umaCategoriaDeDespesa(),
                jaReembolsado, s.chain(), HOJE).amount()).isEqualTo(Money.of("50.00"));
    }

    @Test
    @DisplayName("S-218: reembolso de 1.000 e depois cancelar as restantes (cobrado cairia a 200) é recusado e nada muda")
    void deveRecusarCancelarComReembolsoAcimaDoRestante() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        Transaction compra = s.get(PURCHASE_6X_ID);
        s.refundOf(compra, "1000.00", HOJE, HOJE);

        assertThatThrownBy(() -> CardTransactions.cancelRemainingInstallments(compra, s.chain(), HOJE, NOW, s.activeRefundsOf(compra)))
                .isInstanceOf(RefundExceedsOriginalException.class);
        assertThat(compra.installments()).noneMatch(i -> i.isCanceled());
        assertThat(compra.chargedAmount()).isEqualTo(Money.of("1200.00"));
        assertThat(consumo(s)).isEqualTo(Money.of("200.00"));
    }

    @Test
    @DisplayName("S-218: reembolso de 200 (igual ao que fica cobrado) e depois cancelar as restantes é aceito; o consumo fica em 0,00")
    void deveAceitarCancelarComReembolsoDentroDoRestante() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        Transaction compra = s.get(PURCHASE_6X_ID);
        s.refundOf(compra, "200.00", HOJE, HOJE);

        CardTransactions.cancelRemainingInstallments(compra, s.chain(), HOJE, NOW, s.activeRefundsOf(compra));

        assertThat(compra.chargedAmount()).isEqualTo(Money.of("200.00"));
        assertThat(consumo(s)).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("sem parcela cancelada o teto continua o valor da compra; na despesa é o valor")
    void deveUsarOValorQuandoNadaFoiCancelado() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        assertThat(s.get(PURCHASE_6X_ID).chargedAmount()).isEqualTo(Money.of("1200.00"));
        assertThat(TransactionTestFactoryAccess.despesa().chargedAmount()).isEqualTo(Money.of("100.00"));
    }

    /** Acesso à despesa de referência sem depender da ordem dos imports estáticos. */
    private static final class TransactionTestFactoryAccess {
        static Transaction despesa() {
            return com.moneylog.core.transaction.util.factory.TransactionTestFactory.umaDespesaPix();
        }
    }
}
