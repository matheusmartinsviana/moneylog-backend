package com.moneylog.core.finance.app.cards;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;

/** O cartão com o que a API calcula (T51 CA1, T54 CA1/CA2/CA4/CA5, S-129), sobre o cenário base de D1 §3. */
@DisplayName("CardViews: limite, fatura corrente e os dois comprometidos (S-129)")
class CardViewsTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final LocalDate CLOSING = LocalDate.of(2026, 10, 25);

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();

    private CardDetails details(LocalDate today) {
        return CardViews.of(new Persisted<>(scenario.roxinho, NOW, NOW), scenario.records(), today);
    }

    @Test
    @DisplayName("cartão novo e sem movimento: limite inteiro, fatura corrente vazia (OPEN, id determinístico) e arquivável")
    void deveMostrarCartaoVazio() {
        CardDetails d = details(TODAY);

        assertThat(d.limit().available()).isEqualTo(Money.of("10000.00"));
        assertThat(d.limit().overLimit()).isFalse();
        assertThat(d.creditBalance()).isEqualTo(Money.of("0.00"));
        assertThat(d.usedRatio()).isEqualByComparingTo("0");
        assertThat(d.usedRatio().scale()).isEqualTo(4);
        assertThat(d.totalCommitted()).isEqualTo(Money.of("0.00"));
        assertThat(d.committedFuture()).isEqualTo(Money.of("0.00"));
        assertThat(d.archivable()).isTrue();
        InvoiceView current = d.currentInvoice();
        assertThat(current.id()).isEqualTo(InvoiceIds.of(scenario.roxinho.id(), CLOSING));
        assertThat(current.status()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(current.referenceMonth().toString()).isEqualTo("2026-11");
        assertThat(current.periodStart()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(current.closingDate()).isEqualTo(CLOSING);
        assertThat(current.dueDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(current.total()).isEqualTo(Money.of("0.00"));
        assertThat(current.hasLateEntries()).isFalse();
    }

    @Test
    @DisplayName("compra 6x de 1.200 + à vista de 1.083 em 10/10: fatura 1.283, futuras 1.000, disponível 7.717, uso 0,2283")
    void deveSepararCorrenteEFuturas() {
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();

        CardDetails d = details(TODAY);

        assertThat(d.currentInvoice().status()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(d.currentInvoice().total()).isEqualTo(Money.of("1283.00"));
        assertThat(d.currentInvoice().outstanding()).isEqualTo(Money.of("1283.00"));
        assertThat(d.totalCommitted()).isEqualTo(Money.of("1283.00"));
        assertThat(d.committedFuture()).isEqualTo(Money.of("1000.00"));
        assertThat(d.limit().available()).isEqualTo(Money.of("7717.00"));
        assertThat(d.usedRatio()).isEqualByComparingTo(new BigDecimal("0.2283"));
        assertThat(d.limit().overLimit()).isFalse();
        assertThat(d.archivable()).isFalse();
        // limite - disponível = totalCommitted + committedFuture (com crédito 0)
        assertThat(Money.of("10000.00").subtract(d.limit().available()))
                .isEqualTo(d.totalCommitted().add(d.committedFuture()));
    }

    @Test
    @DisplayName("em 30/10 a fatura de 25/10 está CLOSED: o comprometido é 1.283 + 200 da corrente e as futuras caem para 800")
    void deveMoverParcelasParaOComprometidoQuandoAFaturaAbre() {
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();

        CardDetails d = details(LocalDate.of(2026, 10, 30));

        assertThat(d.currentInvoice().closingDate()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(d.currentInvoice().total()).isEqualTo(Money.of("200.00"));
        assertThat(d.totalCommitted()).isEqualTo(Money.of("1483.00"));
        assertThat(d.committedFuture()).isEqualTo(Money.of("800.00"));
        assertThat(Money.of("10000.00").subtract(d.limit().available())).isEqualTo(Money.of("2283.00"));
    }

    @Test
    @DisplayName("pagamento parcial de 500: o disponível sobe exatamente 500 e a fatura fica com 783")
    void devePagarParcial() {
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();
        scenario.pagarFatura("500.00", CLOSING, LocalDate.of(2026, 10, 26));

        CardDetails d = details(LocalDate.of(2026, 11, 6));

        assertThat(d.limit().available()).isEqualTo(Money.of("8217.00"));
        assertThat(d.totalCommitted()).isEqualTo(Money.of("983.00")); // 783 em atraso + 200 da corrente
        assertThat(d.committedFuture()).isEqualTo(Money.of("800.00"));
    }

    @Test
    @DisplayName("compra acima do limite é aceita e sinalizada: disponível negativo, overLimit e uso limitado a 1")
    void deveSinalizarAcimaDoLimite() {
        scenario.compraNoCartao("12000.00", 1);

        CardDetails d = details(TODAY);

        assertThat(d.limit().available()).isEqualTo(Money.of("-2000.00"));
        assertThat(d.limit().overLimit()).isTrue();
        assertThat(d.usedRatio()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("pagar 1.300 de uma fatura de 1.283 sem parcelas futuras deixa 17,00 de crédito no cartão (e não arquivável)")
    void deveMostrarCreditoNoCartao() {
        scenario.compraNoCartao("200.00", 1);
        scenario.compraAVista1083();
        scenario.pagarFatura("1300.00", CLOSING, LocalDate.of(2026, 10, 26));

        CardDetails d = details(LocalDate.of(2026, 11, 6));

        assertThat(d.creditBalance()).isEqualTo(Money.of("17.00"));
        assertThat(d.limit().available()).isEqualTo(Money.of("10017.00"));
        assertThat(d.usedRatio()).isEqualByComparingTo("0");
        assertThat(d.archivable()).isFalse();
    }

    @Test
    @DisplayName("pagar 1.300 de uma fatura de 1.283 com parcelas futuras: o crédito de 17,00 abate a seguinte e creditBalance é 0,00")
    void deveAbaterCreditoNaFaturaSeguinte() {
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();
        scenario.pagarFatura("1300.00", CLOSING, LocalDate.of(2026, 10, 26));

        CardDetails d = details(LocalDate.of(2026, 10, 30));

        assertThat(d.creditBalance()).isEqualTo(Money.of("0.00"));
        assertThat(d.currentInvoice().carryIn()).isEqualTo(Money.of("17.00"));
        assertThat(d.currentInvoice().outstanding()).isEqualTo(Money.of("183.00"));
    }

    @Test
    @DisplayName("tudo pago e sem parcela futura: arquivável")
    void deveSerArquivavelQuandoQuitado() {
        scenario.compraNoCartao("200.00", 1);
        scenario.pagarFatura("200.00", CLOSING, LocalDate.of(2026, 10, 26));

        assertThat(details(LocalDate.of(2026, 11, 6)).archivable()).isTrue();
    }

    @Test
    @DisplayName("cartão arquivado nunca é arquivável de novo")
    void naoDeveSerArquivavelSeArquivado() {
        CreditCard archived = com.moneylog.core.card.util.factory.CreditCardTestFactory.umCartaoArquivado();

        CardDetails d = CardViews.of(new Persisted<>(archived, NOW, NOW), scenario.records(), TODAY);

        assertThat(d.card().status()).isEqualTo(CardStatus.ARCHIVED);
        assertThat(d.archivable()).isFalse();
    }

    @Test
    @DisplayName("ofAll devolve um detalhe por cartão, na ordem recebida, do mesmo carregamento")
    void deveMontarVariosCartoes() {
        scenario.caso2CompraParcelada();

        List<CardDetails> all = CardViews.ofAll(List.of(new Persisted<>(scenario.roxinho, NOW, NOW)), scenario.records(), TODAY);

        assertThat(all).hasSize(1);
        assertThat(all.getFirst().committedFuture()).isEqualTo(Money.of("1000.00"));
    }
}
