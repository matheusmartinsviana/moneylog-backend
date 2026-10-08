package com.moneylog.core.card.domain;

import static com.moneylog.core.card.util.factory.InvoiceScenario.DEC_CLOSING;
import static com.moneylog.core.card.util.factory.InvoiceScenario.NOV_CLOSING;
import static com.moneylog.core.card.util.factory.InvoiceScenario.PURCHASE_6X_ID;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.moneylog.core.card.exception.CardExceptions.NoInstallmentsToCancelException;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.card.util.factory.InvoiceScenario;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.CardTransactionVoidPolicy;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.exception.TransactionExceptions.InvalidTransactionOperationException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionVoidRefusedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidPolicyNotApplicableException;
import com.moneylog.core.transaction.util.factory.TransactionTestFactory;

@DisplayName("Limite disponível, cancelamento de parcelas e guardas de estorno do cartão (T6b)")
class CardLimitAndVoidTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static AvailableLimit limite(InvoiceScenario s) {
        return AvailableLimitCalculator.calculate(s.card(), s.postings());
    }

    private static CardTransactionVoidPolicy policy(InvoiceScenario s, LocalDate hoje) {
        return new CardTransactionVoidPolicy(s.card(), s.chain(), hoje);
    }

    @Nested
    @DisplayName("CA1: limite disponível (S-46, S-11)")
    class Limite {

        @Test
        @DisplayName("limite 10.000 e compra de 1.200 em 6x → 8.800; pagando a fatura com a parcela 1 (200) → 9.000")
        void deveConsumirOValorTotalEDevolverNoPagamento() {
            InvoiceScenario s = InvoiceScenario.comCompraParcelada();
            assertThat(limite(s).available()).isEqualTo(Money.of("8800.00"));
            s.pay("200.00", NOV_CLOSING, d("2026-11-05"));
            assertThat(limite(s).available()).isEqualTo(Money.of("9000.00"));
            assertThat(limite(s).overLimit()).isFalse();
        }

        @Test
        @DisplayName("pagamento parcial de 500 numa fatura de 1.283 sobe o disponível exatamente 500")
        void deveDevolverOPagamentoParcial() {
            InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
            Money antes = limite(s).available();
            s.pay("500.00", NOV_CLOSING, d("2026-11-01"));
            assertThat(limite(s).available().subtract(antes)).isEqualTo(Money.of("500.00"));
        }

        @Test
        @DisplayName("limite 1.000, compra de 200 e pagamento de 300 → disponível 1.100 e crédito no cartão 100")
        void devePassarDoLimiteComCredito() {
            InvoiceScenario s = InvoiceScenario.vazio(CreditCardTestFactory.umCartaoComLimite(Money.of("1000.00"), 25, 5));
            s.buyByCalendar("200.00", 1, d("2026-10-10"));
            s.pay("300.00", NOV_CLOSING, d("2026-10-20"));

            AvailableLimit limit = limite(s);
            assertThat(limit.available()).isEqualTo(Money.of("1100.00"));
            assertThat(limit.creditBalance()).isEqualTo(Money.of("100.00"));
            assertThat(s.chain().creditBalance()).isEqualTo(limit.creditBalance());
        }

        @Test
        @DisplayName("compras acima do limite → disponível negativo com overLimit = true, sem exceção")
        void deveSinalizarLimiteEstourado() {
            InvoiceScenario s = InvoiceScenario.vazio(CreditCardTestFactory.umCartaoComLimite(Money.of("1000.00"), 25, 5));
            s.buyByCalendar("1500.00", 3, d("2026-10-10"));
            AvailableLimit limit = limite(s);
            assertThat(limit.available()).isEqualTo(Money.of("-500.00"));
            assertThat(limit.overLimit()).isTrue();
            assertThat(limit.creditBalance()).isEqualTo(Money.of("0.00"));
        }
    }

    @Test
    @DisplayName("CA2: com a parcela 1 em fatura CLOSED, cancelar em 26/10 cancela 2..6, desativa só os postings delas, mantém Σ = 0 e devolve 1.000 de limite")
    void deveCancelarAsParcelasRestantes() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        Transaction compra = s.get(PURCHASE_6X_ID);
        Money antes = limite(s).available();

        CardTransactions.cancelRemainingInstallments(compra, s.chain(), d("2026-10-26"), NOW);

        List<TransactionInstallment> parcelas = compra.installments();
        assertThat(parcelas.getFirst().isCanceled()).isFalse();
        assertThat(parcelas.subList(1, 6)).allMatch(TransactionInstallment::isCanceled);
        assertThat(parcelas.get(3).statusFor(InvoiceStatus.FUTURE)).isEqualTo(InstallmentStatus.CANCELED);
        assertThat(compra.entries()).filteredOn(LedgerEntry::active).hasSize(2)
                .allMatch(e -> e.source().installmentId().equals(parcelas.getFirst().id()));
        assertThat(Money.sum(compra.postings().stream().map(Posting::amount).toList(), CurrencyCode.BRL)).isEqualTo(Money.of("0.00"));
        assertThat(compra.status()).isEqualTo(TransactionStatus.ACTIVE);
        assertThat(limite(s).available().subtract(antes)).isEqualTo(Money.of("1000.00"));
        assertThat(s.chain().invoice(DEC_CLOSING).total()).isEqualTo(Money.of("0.00"));

        assertThatThrownBy(() -> CardTransactions.cancelRemainingInstallments(compra, s.chain(), d("2026-10-26"), NOW))
                .isInstanceOf(NoInstallmentsToCancelException.class);
        assertThatThrownBy(() -> CardTransactions.cancelRemainingInstallments(TransactionTestFactory.umaDespesaPix(), s.chain(), d("2026-10-26"), NOW))
                .isInstanceOf(InvalidTransactionOperationException.class);
    }

    @ParameterizedTest(name = "estorno em {0}: aceito = {1}")
    @CsvSource({"2026-10-24, true", "2026-10-25, false", "2026-11-06, false"})
    @DisplayName("CA3: compra de 10/10 é estornável em 24/10 e não em 25/10 (relógio fixo, S-36)")
    void deveGuardarOEstornoDaCompra(LocalDate hoje, boolean aceito) {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        Transaction compra = s.get(PURCHASE_6X_ID);

        if (aceito) {
            s.voidTx(compra, "duplicada", hoje);
            assertThat(compra.status()).isEqualTo(TransactionStatus.VOIDED);
            assertThat(compra.installments()).allMatch(TransactionInstallment::isCanceled);
            assertThat(compra.postings()).isEmpty();
        } else {
            assertThatThrownBy(() -> s.voidTx(compra, "duplicada", hoje))
                    .isInstanceOf(TransactionVoidRefusedException.class)
                    .extracting(e -> ((DomainException) e).code()).isEqualTo("TRANSACTION_IN_CLOSED_INVOICE");
            assertThat(compra.status()).isEqualTo(TransactionStatus.ACTIVE);
            assertThat(compra.installments()).noneMatch(TransactionInstallment::isCanceled);
        }
    }

    @Test
    @DisplayName("CA3: a compra com a parcela 1 em fatura PAID também é recusada")
    void deveRecusarCompraEmFaturaPaga() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        s.pay("200.00", NOV_CLOSING, d("2026-10-26"));
        assertThat(s.chain().statusOf(NOV_CLOSING, d("2026-10-27"))).isEqualTo(InvoiceStatus.PAID);
        assertThatThrownBy(() -> s.voidTx(s.get(PURCHASE_6X_ID), "x", d("2026-10-27")))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("TRANSACTION_IN_CLOSED_INVOICE");
    }

    @Test
    @DisplayName("CA4: reembolso no cartão em fatura OPEN é estornável; em fatura fechada é recusado; pagamento é sempre estornável")
    void deveGuardarReembolsoEPagamento() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        Transaction aberto = s.refund("50.00", d("2026-10-15"), d("2026-10-15"));
        Transaction outro = s.refund("30.00", d("2026-10-16"), d("2026-10-16"));
        s.voidTx(aberto, "x", d("2026-10-20"));
        assertThat(aberto.isActive()).isFalse();

        assertThatThrownBy(() -> s.voidTx(outro, "x", d("2026-10-26")))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("TRANSACTION_IN_CLOSED_INVOICE");

        Transaction pagamento = s.pay("170.00", NOV_CLOSING, d("2026-11-01"));
        assertThat(s.chain().statusOf(NOV_CLOSING, d("2026-11-10"))).isEqualTo(InvoiceStatus.PAID);
        s.voidTx(pagamento, "não compensou", d("2026-11-10"));
        assertThat(s.chain().statusOf(NOV_CLOSING, d("2026-11-10"))).isEqualTo(InvoiceStatus.OVERDUE);
    }

    @Test
    @DisplayName("a policy do cartão não serve para movimentação de conta nem de outro cartão")
    void deveRecusarTipoErrado() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        assertThatThrownBy(() -> policy(s, d("2026-10-10")).evaluate(TransactionTestFactory.umaDespesaPix()))
                .isInstanceOf(VoidPolicyNotApplicableException.class);
    }

    @Test
    @DisplayName("CA5: compra de 90 lançada por engano em fatura CLOSED não paga + reembolso de 90 no mesmo ciclo: mesma fatura, total volta e consumo líquido 0")
    void deveCorrigirCompraTardiaComReembolso() {
        InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
        Transaction engano = s.buy("90.00", 1, d("2026-10-20"), d("2026-10-27"));
        assertThatThrownBy(() -> s.voidTx(engano, "engano", d("2026-10-27")))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("TRANSACTION_IN_CLOSED_INVOICE");

        Transaction reembolso = s.refund("90.00", d("2026-10-20"), d("2026-10-27"));

        assertThat(reembolso.invoiceClosingDate()).isEqualTo(NOV_CLOSING);
        assertThat(reembolso.lateEntry()).isTrue();
        assertThat(s.chain().invoice(NOV_CLOSING).total()).isEqualTo(Money.of("1283.00"));
        Money consumoLiquido = Money.sum(List.of(engano, reembolso).stream().flatMap(t -> t.postings().stream())
                .filter(p -> p.bookType() == BookType.EXPENSE && p.competence().equals(Competence.of(2026, 10)))
                .map(Posting::amount).toList(), CurrencyCode.BRL);
        assertThat(consumoLiquido).isEqualTo(Money.of("0.00"));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("sem limite definido (ADR-014): disponível nulo, sem estouro, crédito preservado; limite zero continua inválido")
    void deveCalcularSemLimite() {
        var card = com.moneylog.core.card.util.factory.CreditCardTestFactory.umCartaoComLimite(null, 25, 5);
        assertThat(card.limit()).isNull();
        var limit = AvailableLimitCalculator.calculate(card, java.util.List.of());
        assertThat(limit.available()).isNull();
        assertThat(limit.overLimit()).isFalse();
        assertThat(limit.creditBalance().isZero()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> com.moneylog.core.card.util.factory.CreditCardTestFactory
                .umCartaoComLimite(com.moneylog.core.sk.money.Money.of("0.00"), 25, 5))
                .isInstanceOf(com.moneylog.core.card.exception.CardExceptions.InvalidCardLimitException.class);
        card.updateDetails("Roxinho", "Nubank", com.moneylog.core.sk.money.Money.of("1000.00"), null);
        assertThat(card.limit()).isNotNull();
        card.updateDetails("Roxinho", "Nubank", null, null);
        assertThat(card.limit()).isNull();
    }
}
