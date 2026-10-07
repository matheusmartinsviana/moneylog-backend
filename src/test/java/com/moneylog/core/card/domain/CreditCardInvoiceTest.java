package com.moneylog.core.card.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.NUBANK_ID;
import static com.moneylog.core.card.util.factory.CreditCardTestFactory.ROXINHO_ID;
import static com.moneylog.core.card.util.factory.InvoiceScenario.DEC_CLOSING;
import static com.moneylog.core.card.util.factory.InvoiceScenario.NOV_CLOSING;
import static com.moneylog.core.card.util.factory.InvoiceScenario.PURCHASE_6X_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.ALIMENTACAO_ID;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.moneylog.core.card.exception.CardExceptions.InvalidInvoiceOperationException;
import com.moneylog.core.card.util.factory.InvoiceScenario;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.VoidDecision;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

@DisplayName("Fatura: status derivado, alocação, pagamento e crédito transportado (T6a)")
class CreditCardInvoiceTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @ParameterizedTest(name = "hoje {0} → {1}")
    @CsvSource({"2026-09-24, FUTURE", "2026-09-25, OPEN", "2026-10-24, OPEN", "2026-10-25, CLOSED", "2026-11-05, CLOSED", "2026-11-06, OVERDUE"})
    @DisplayName("CA1: status derivado da fatura ref. 2026-11 de 1.283 sem pagamentos (S-37)")
    void deveDerivarOStatus(LocalDate hoje, InvoiceStatus esperado) {
        CreditCardInvoice nov = InvoiceScenario.comFaturaDe1283().chain().invoice(NOV_CLOSING);
        assertThat(nov.total()).isEqualTo(Money.of("1283.00"));
        assertThat(nov.statusAt(hoje)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("CA1: com outstanding = 0 em 25/10 a fatura é PAID; não existe close/markOverdue nem campo de status")
    void deveSerPagaComSaldoZero() {
        InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
        s.pay("1283.00", NOV_CLOSING, d("2026-10-20"));
        assertThat(s.chain().statusOf(NOV_CLOSING, d("2026-10-25"))).isEqualTo(InvoiceStatus.PAID);
        assertThat(Stream.of(CreditCardInvoice.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("close", "markOverdue", "setStatus");
        assertThat(Stream.of(CreditCardInvoice.class.getDeclaredFields()).map(java.lang.reflect.Field::getName)).doesNotContain("status");
    }

    @Test
    @DisplayName("CA2: pagar 1.283 em 05/11 gera CARD +1.283 / ACCOUNT −1.283, nenhum EXPENSE; fatura e parcelas PAID")
    void devePagarAFaturaInteira() {
        InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
        Transaction pagamento = s.pay("1283.00", NOV_CLOSING, d("2026-11-05"));

        assertThat(pagamento.type()).isEqualTo(TransactionType.INVOICE_PAYMENT);
        assertThat(pagamento.postings()).containsExactly(
                new Posting(BookRef.card(ROXINHO_ID), Money.of("1283.00"), d("2026-11-05")),
                new Posting(BookRef.account(NUBANK_ID), Money.of("-1283.00"), d("2026-11-05")));
        assertThat(pagamento.postings()).noneMatch(p -> p.bookType() == BookType.EXPENSE);
        InvoiceChain chain = s.chain();
        assertThat(chain.statusOf(NOV_CLOSING, d("2026-11-05"))).isEqualTo(InvoiceStatus.PAID);
        assertThat(s.transactions()).flatMap(Transaction::installments)
                .filteredOn(i -> i.invoiceClosingDate().equals(NOV_CLOSING))
                .hasSize(2)
                .allSatisfy(i -> assertThat(i.statusFor(chain.statusOf(NOV_CLOSING, d("2026-11-05")))).isEqualTo(InstallmentStatus.PAID));
    }

    @Test
    @DisplayName("CA3: pagamento parcial de 500 deixa CLOSED com 783 e sem juros; em 06/11 OVERDUE (parcelas OVERDUE); +783 fica PAID")
    void deveAceitarPagamentoParcial() {
        InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
        s.pay("500.00", NOV_CLOSING, d("2026-11-01"));

        CreditCardInvoice nov = s.chain().invoice(NOV_CLOSING);
        assertThat(nov.statusAt(d("2026-11-01"))).isEqualTo(InvoiceStatus.CLOSED);
        assertThat(nov.outstanding()).isEqualTo(Money.of("783.00"));
        assertThat(nov.statusAt(d("2026-11-06"))).isEqualTo(InvoiceStatus.OVERDUE);
        assertThat(s.get(PURCHASE_6X_ID).installments().getFirst().statusFor(nov.statusAt(d("2026-11-06")))).isEqualTo(InstallmentStatus.OVERDUE);
        assertThat(s.chain().invoice(DEC_CLOSING).carryIn()).isEqualTo(Money.of("0.00"));

        s.pay("783.00", NOV_CLOSING, d("2026-11-06"));
        assertThat(s.chain().statusOf(NOV_CLOSING, d("2026-11-06"))).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("CA4: pagar 1.300 numa fatura de 1.283 deixa PAID com creditCarry 17; a ref. 2026-12 (200) tem carryIn 17 e outstanding 183 (S-16)")
    void deveTransportarOCredito() {
        InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
        s.pay("1300.00", NOV_CLOSING, d("2026-11-05"));
        InvoiceChain chain = s.chain();

        assertThat(chain.statusOf(NOV_CLOSING, d("2026-11-05"))).isEqualTo(InvoiceStatus.PAID);
        assertThat(chain.invoice(NOV_CLOSING).creditCarry()).isEqualTo(Money.of("17.00"));
        CreditCardInvoice dez = chain.invoice(DEC_CLOSING);
        assertThat(dez.total()).isEqualTo(Money.of("200.00"));
        assertThat(dez.carryIn()).isEqualTo(Money.of("17.00"));
        assertThat(dez.outstanding()).isEqualTo(Money.of("183.00"));
        assertThat(dez.referenceMonth().toString()).isEqualTo("2026-12");
    }

    @Test
    @DisplayName("CA4: pagar 300 na fatura OPEN de 200 em 20/10 é aceito (S-15); em 25/10 ela está PAID com creditCarry 100")
    void deveAceitarAntecipacao() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        s.pay("300.00", NOV_CLOSING, d("2026-10-20"));
        InvoiceChain chain = s.chain();

        assertThat(chain.statusOf(NOV_CLOSING, d("2026-10-20"))).isEqualTo(InvoiceStatus.OPEN);
        assertThat(chain.statusOf(NOV_CLOSING, d("2026-10-25"))).isEqualTo(InvoiceStatus.PAID);
        assertThat(chain.invoice(NOV_CLOSING).creditCarry()).isEqualTo(Money.of("100.00"));
    }

    @Test
    @DisplayName("CA4: pagar fatura FUTURE ou PAID lança InvalidInvoiceOperationException com INVOICE_NOT_PAYABLE")
    void deveRecusarPagamentoDeFaturaFuturaOuPaga() {
        InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
        assertThatThrownBy(() -> s.pay("10.00", DEC_CLOSING, d("2026-10-10")))
                .isInstanceOf(InvalidInvoiceOperationException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("INVOICE_NOT_PAYABLE");
        s.pay("1283.00", NOV_CLOSING, d("2026-11-01"));
        assertThatThrownBy(() -> s.pay("10.00", NOV_CLOSING, d("2026-11-02")))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("INVOICE_NOT_PAYABLE");
    }

    @Test
    @DisplayName("CA5: reembolso de 80 no cartão na fatura OPEN de 200 gera CARD +80 / EXPENSE −80 e o total cai para 120")
    void deveCreditarReembolsoNoCartao() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        Transaction reembolso = s.refund("80.00", d("2026-10-15"), d("2026-10-15"));

        assertThat(reembolso.postings()).containsExactly(
                new Posting(BookRef.card(ROXINHO_ID), Money.of("80.00"), d("2026-10-15")),
                new Posting(BookRef.expense(ALIMENTACAO_ID), Money.of("-80.00"), d("2026-10-15")));
        assertThat(reembolso.invoiceClosingDate()).isEqualTo(NOV_CLOSING);
        assertThat(reembolso.lateEntry()).isFalse();
        assertThat(s.chain().invoice(NOV_CLOSING).total()).isEqualTo(Money.of("120.00"));
    }

    @Test
    @DisplayName("CA5: reembolso de 250 numa fatura de 200 é aceito: total −50, outstanding 0, creditCarry 50 (S-18)")
    void deveAceitarReembolsoMaiorQueAFatura() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        s.refund("250.00", d("2026-10-15"), d("2026-10-15"));
        CreditCardInvoice nov = s.chain().invoice(NOV_CLOSING);

        assertThat(nov.total()).isEqualTo(Money.of("-50.00"));
        assertThat(nov.outstanding()).isEqualTo(Money.of("0.00"));
        assertThat(nov.creditCarry()).isEqualTo(Money.of("50.00"));
        assertThat(s.chain().invoice(DEC_CLOSING).carryIn()).isEqualTo(Money.of("50.00"));
    }

    @Nested
    @DisplayName("CA6: lançamento tardio (S-14)")
    class LancamentoTardio {

        @Test
        @DisplayName("fatura CLOSED e não paga: compra de 90 datada de 20/10 registrada em 27/10 entra nela com lateEntry e o total vai a 1.373")
        void deveEntrarNaFaturaFechadaNaoPaga() {
            InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
            Transaction tardia = s.buy("90.00", 1, d("2026-10-20"), d("2026-10-27"));

            TransactionInstallment parcela = tardia.installments().getFirst();
            assertThat(parcela.invoiceClosingDate()).isEqualTo(NOV_CLOSING);
            assertThat(parcela.lateEntry()).isTrue();
            CreditCardInvoice nov = s.chain().invoice(NOV_CLOSING);
            assertThat(nov.total()).isEqualTo(Money.of("1373.00"));
            assertThat(nov.hasLateEntries()).isTrue();
        }

        @Test
        @DisplayName("fatura já PAID: a compra entra na corrente (ref. 2026-12) com lateEntry e as parcelas seguintes seguem a partir dela")
        void deveIrParaACorrenteQuandoAFaturaFoiPaga() {
            InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
            s.pay("1373.00", NOV_CLOSING, d("2026-10-26"));
            Transaction tardia = s.buy("90.00", 3, d("2026-10-20"), d("2026-10-27"));

            List<TransactionInstallment> parcelas = tardia.installments();
            assertThat(parcelas.get(0).invoiceClosingDate()).isEqualTo(DEC_CLOSING);
            assertThat(parcelas.get(0).lateEntry()).isTrue();
            assertThat(parcelas.get(1).invoiceClosingDate()).isEqualTo(d("2026-12-25"));
            assertThat(parcelas.get(1).lateEntry()).isFalse();
            assertThat(parcelas.get(2).invoiceClosingDate()).isEqualTo(d("2027-01-25"));
            assertThat(parcelas.get(0).competence().toString()).isEqualTo("2026-10");
            InvoiceChain chain = s.chain();
            assertThat(chain.invoice(NOV_CLOSING).creditCarry()).isEqualTo(Money.of("90.00"));
            assertThat(chain.invoice(NOV_CLOSING).total()).isEqualTo(Money.of("1283.00"));
            // ref. dez: parcela 2/6 (200) + parcela 1/3 tardia (30) − crédito transportado (90) = 140
            assertThat(chain.invoice(DEC_CLOSING).total()).isEqualTo(Money.of("230.00"));
            assertThat(chain.invoice(DEC_CLOSING).outstanding()).isEqualTo(Money.of("140.00"));
        }

        @Test
        @DisplayName("data num ciclo OPEN ou FUTURE: lateEntry = false")
        void naoDeveSerTardioEmFaturaAberta() {
            InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
            assertThat(s.buy("10.00", 1, d("2026-10-20"), d("2026-10-21")).installments().getFirst().lateEntry()).isFalse();
            assertThat(s.buy("10.00", 1, d("2026-12-20"), d("2026-10-21")).installments().getFirst().lateEntry()).isFalse();
            assertThat(s.refund("10.00", d("2026-10-20"), d("2026-10-27")).lateEntry()).isTrue();
        }

        @Test
        @DisplayName("parcela k cuja fatura já foi paga também vai para a corrente (S-50)")
        void naoDevePorParcelaEmFaturaPaga() {
            InvoiceScenario s = InvoiceScenario.comCompraParcelada();
            s.pay("200.00", NOV_CLOSING, d("2026-11-05"));
            s.pay("200.00", DEC_CLOSING, d("2026-12-05"));
            // hoje 10/12: ref. nov e dez pagas; compra datada de 20/10 em 3x
            Transaction tardia = s.buy("300.00", 3, d("2026-10-20"), d("2026-12-10"));
            assertThat(tardia.installments()).extracting(TransactionInstallment::invoiceClosingDate)
                    .containsExactly(d("2026-12-25"), d("2027-01-25"), d("2027-02-25"));
            assertThat(tardia.installments()).extracting(TransactionInstallment::lateEntry).containsExactly(true, false, false);
        }
    }

    @Test
    @DisplayName("CA7: fatura PAID cujo único pagamento é estornado volta a CLOSED em 01/11 e OVERDUE em 06/11, com o outstanding reaberto")
    void deveReabrirAoEstornarOPagamento() {
        InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
        Transaction pagamento = s.pay("1283.00", NOV_CLOSING, d("2026-10-30"));
        assertThat(s.chain().statusOf(NOV_CLOSING, d("2026-11-01"))).isEqualTo(InvoiceStatus.PAID);

        s.voidTx(pagamento, "pagamento não compensou", d("2026-11-01"));

        CreditCardInvoice nov = s.chain().invoice(NOV_CLOSING);
        assertThat(nov.statusAt(d("2026-11-01"))).isEqualTo(InvoiceStatus.CLOSED);
        assertThat(nov.statusAt(d("2026-11-06"))).isEqualTo(InvoiceStatus.OVERDUE);
        assertThat(nov.outstanding()).isEqualTo(Money.of("1283.00"));
    }

    static Stream<Arguments> cenarios() {
        return Stream.<Arguments>of(
                Arguments.of("pagamento parcial", (Consumer<InvoiceScenario>) s -> s.pay("500.00", NOV_CLOSING, d("2026-11-01"))),
                Arguments.of("pagamento a maior", (Consumer<InvoiceScenario>) s -> s.pay("1300.00", NOV_CLOSING, d("2026-11-05"))),
                Arguments.of("antecipado", (Consumer<InvoiceScenario>) s -> s.pay("2000.00", NOV_CLOSING, d("2026-10-20"))),
                Arguments.of("reembolso maior que a fatura", (Consumer<InvoiceScenario>) s -> s.refund("1500.00", d("2026-10-15"), d("2026-10-15"))),
                Arguments.of("lançamento tardio", (Consumer<InvoiceScenario>) s -> s.buy("90.00", 2, d("2026-10-20"), d("2026-10-27"))),
                Arguments.of("tardio com fatura paga", (Consumer<InvoiceScenario>) s -> {
                    s.pay("1373.00", NOV_CLOSING, d("2026-10-26"));
                    s.buy("90.00", 1, d("2026-10-20"), d("2026-10-27"));
                }),
                Arguments.of("estorno de pagamento", (Consumer<InvoiceScenario>) s -> {
                    Transaction t = s.pay("1000.00", NOV_CLOSING, d("2026-11-01"));
                    s.pay("300.00", NOV_CLOSING, d("2026-11-02"));
                    s.voidTx(t, "x", d("2026-11-02"));
                }),
                Arguments.of("seis faturas pagas, uma a maior e outra a menor", (Consumer<InvoiceScenario>) s -> {
                    s.pay("1300.00", NOV_CLOSING, d("2026-11-05"));
                    s.pay("183.00", DEC_CLOSING, d("2026-12-05"));
                    for (int k = 2; k < 6; k++) {
                        s.pay("200.00", NOV_CLOSING.plusMonths(k), NOV_CLOSING.plusMonths(k).plusDays(11));
                    }
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cenarios")
    @DisplayName("CA8: Σ outstanding − creditCarry da última = Σ total − Σ paid = −(saldo do livro CARD em todas as datas)")
    void deveManterAInvarianteDoCartao(String nome, Consumer<InvoiceScenario> operacao) {
        InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
        operacao.accept(s);
        List<CreditCardInvoice> faturas = s.chain().invoices();

        Money outstanding = Money.sum(faturas.stream().map(CreditCardInvoice::outstanding).toList(), CurrencyCode.BRL);
        Money total = Money.sum(faturas.stream().map(CreditCardInvoice::total).toList(), CurrencyCode.BRL);
        Money paid = Money.sum(faturas.stream().map(CreditCardInvoice::paid).toList(), CurrencyCode.BRL);
        Money lastCarry = faturas.getLast().creditCarry();

        assertThat(outstanding.subtract(lastCarry)).isEqualTo(total.subtract(paid));
        assertThat(total.subtract(paid)).isEqualTo(s.cardBookBalance().negate());
        assertThat(s.chain().creditBalance()).isEqualTo(lastCarry);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InvoiceStatus.class)
    @DisplayName("CA9: matriz status × operação (addItem, registerPayment) com code estável")
    void deveRespeitarAMatriz(InvoiceStatus status) {
        InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
        LocalDate hoje = switch (status) {
            case FUTURE -> d("2026-09-20");
            case OPEN -> d("2026-10-10");
            case CLOSED -> d("2026-10-27");
            case OVERDUE -> d("2026-11-06");
            case PAID -> {
                s.pay("1283.00", NOV_CLOSING, d("2026-10-26"));
                yield d("2026-10-27");
            }
        };
        assertThat(s.chain().statusOf(NOV_CLOSING, hoje)).isEqualTo(status);
        UUID tx = UUID.fromString("00000000-0000-0000-0000-0000000c9999");
        InvoiceItem normal = new InvoiceItem(tx, null, InvoiceItemKind.INSTALLMENT, Money.of("1.00"), hoje, NOV_CLOSING, false, true);
        InvoiceItem tardio = new InvoiceItem(tx, null, InvoiceItemKind.INSTALLMENT, Money.of("1.00"), hoje, NOV_CLOSING, true, true);
        InvoicePayment pagamento = new InvoicePayment(tx, NOV_CLOSING, Money.of("1.00"), hoje, true);

        switch (status) {
            case FUTURE, OPEN -> {
                s.chain().addItem(normal, hoje);
                s.chain().addItem(tardio, hoje);
            }
            case CLOSED, OVERDUE -> {
                assertCode(() -> s.chain().addItem(normal, hoje), "INVOICE_CLOSED_REQUIRES_LATE_ENTRY");
                s.chain().addItem(tardio, hoje);
            }
            case PAID -> {
                assertCode(() -> s.chain().addItem(normal, hoje), "INVOICE_PAID");
                assertCode(() -> s.chain().addItem(tardio, hoje), "INVOICE_PAID");
            }
        }
        if (status == InvoiceStatus.FUTURE || status == InvoiceStatus.PAID) {
            assertCode(() -> s.chain().registerPayment(pagamento, hoje), "INVOICE_NOT_PAYABLE");
        } else {
            InvoiceChain chain = s.chain();
            chain.registerPayment(pagamento, hoje);
            assertThat(chain.invoice(NOV_CLOSING).paid()).isEqualTo(Money.of("1.00"));
        }
    }

    @Test
    @DisplayName("cadeia: corrente, desativação de transação/parcela, cadeia vazia")
    void deveOperarACadeia() {
        InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
        InvoiceChain chain = s.chain();
        assertThat(chain.current(d("2026-10-10")).closingDate()).isEqualTo(NOV_CLOSING);
        chain.deactivateTransaction(PURCHASE_6X_ID);
        assertThat(chain.invoice(NOV_CLOSING).total()).isEqualTo(Money.of("1083.00"));
        InvoiceChain outra = s.chain();
        outra.deactivateInstallment(TransactionInstallment.idFor(PURCHASE_6X_ID, 2));
        assertThat(outra.invoice(DEC_CLOSING).total()).isEqualTo(Money.of("0.00"));
        InvoiceChain vazia = InvoiceChain.empty(s.card());
        assertThat(vazia.invoices()).isEmpty();
        assertThat(vazia.creditBalance()).isEqualTo(Money.of("0.00"));
        CreditCardInvoice inv = chain.invoice(NOV_CLOSING);
        assertThat(inv.dueDate()).isEqualTo(d("2026-11-05"));
        assertThat(inv.items()).isNotEmpty();
        assertThat(inv.payments()).isEmpty();
        assertThat(inv.period().closingDate()).isEqualTo(NOV_CLOSING);
        assertThat(inv.toString()).contains("total");
        assertThat(new InvoicePayment(PURCHASE_6X_ID, NOV_CLOSING, Money.of("1.00"), NOV_CLOSING, true).deactivate().active()).isFalse();
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(InvalidInvoiceOperationException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo(code);
    }
}
