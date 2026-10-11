package com.moneylog.core.card.domain;

import static com.moneylog.core.card.util.factory.InvoiceScenario.NOV_CLOSING;
import static com.moneylog.core.card.util.factory.InvoiceScenario.NOW;
import static com.moneylog.core.card.util.factory.InvoiceScenario.PURCHASE_6X_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.exception.CardExceptions.InvoiceChainInconsistentException;
import com.moneylog.core.card.exception.CardExceptions.InvoiceClosingDateNotFoundException;
import com.moneylog.core.card.util.factory.InvoiceScenario;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.CardTransactionVoidPolicy;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionVoiding;
import com.moneylog.core.transaction.domain.VoidContext;

@DisplayName("Cadeia de faturas: cartão e data de fechamento validados (A3)")
class InvoiceChainGuardsTest {

    private static final UUID OUTRO_CARTAO_ID = UUID.fromString("00000000-0000-0000-0000-00000000d0ff");

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static TransactionHeader header(String id, String date) {
        return TransactionHeader.of(UUID.fromString(id), USER_ID, d(date), null);
    }

    private static InvoiceChain cadeiaDeOutroCartao() {
        CreditCard outro = CreditCard.create(OUTRO_CARTAO_ID, USER_ID, "Outro", null, Money.of("1000.00"), 25, 5, null, d("2026-09-01"));
        return InvoiceChain.empty(outro);
    }

    @Test
    @DisplayName("pagamento com data que não é fechamento (24/10) é recusado com INVOICE_NOT_FOUND (422) e nada é criado")
    void deveRecusarPagamentoEmDataQueNaoEFechamento() {
        InvoiceScenario s = InvoiceScenario.comFaturaDe1283();
        assertThatThrownBy(() -> s.chain().ensurePayable(d("2026-10-24"), d("2026-10-26")))
                .isInstanceOf(InvoiceClosingDateNotFoundException.class)
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("INVOICE_NOT_FOUND");
        assertThatThrownBy(() -> s.pay("1283.00", d("2026-10-24"), d("2026-10-26")))
                .isInstanceOf(InvoiceClosingDateNotFoundException.class);
        assertThat(s.transactions()).hasSize(2);
        assertThatThrownBy(() -> s.chain().ensureCanReceiveItem(d("2026-11-24"), false, d("2026-10-26")))
                .isInstanceOf(InvoiceClosingDateNotFoundException.class);
        s.chain().ensurePayable(NOV_CLOSING, d("2026-10-26"));
    }

    @Test
    @DisplayName("fábricas do cartão recusam cadeia de outro cartão (compra, pagamento, reembolso e cancelamento de parcelas)")
    void deveRecusarCadeiaDeOutroCartao() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        InvoiceChain alheia = cadeiaDeOutroCartao();
        LocalDate hoje = d("2026-10-26");

        assertThatThrownBy(() -> CardTransactions.purchase(header("00000000-0000-0000-0000-0000000ac001", "2026-10-20"), Money.of("10.00"),
                s.card(), CategoryTestFactory.umaCategoriaDeDespesa(), 1, alheia, hoje))
                .isInstanceOf(InvoiceChainInconsistentException.class);
        assertThatThrownBy(() -> CardTransactions.invoicePayment(header("00000000-0000-0000-0000-0000000ac002", "2026-10-26"),
                Money.of("200.00"), AccountTestFactory.umaContaCorrente(), s.card(), NOV_CLOSING, alheia, hoje))
                .isInstanceOf(InvoiceChainInconsistentException.class);
        assertThatThrownBy(() -> CardTransactions.refundToCard(header("00000000-0000-0000-0000-0000000ac003", "2026-10-20"),
                Money.of("10.00"), s.card(), CategoryTestFactory.umaCategoriaDeDespesa(), null, alheia, hoje))
                .isInstanceOf(InvoiceChainInconsistentException.class);
        Transaction compra = s.get(PURCHASE_6X_ID);
        assertThatThrownBy(() -> CardTransactions.cancelRemainingInstallments(compra, alheia, hoje, NOW))
                .isInstanceOf(InvoiceChainInconsistentException.class);
        assertThat(compra.installments()).noneMatch(TransactionInstallment::isCanceled);
    }

    @Test
    @DisplayName("guarda de estorno recusa cadeia de outro cartão; a compra continua ativa")
    void deveRecusarGuardaComCadeiaDeOutroCartao() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        Transaction compra = s.get(PURCHASE_6X_ID);
        assertThatThrownBy(() -> new CardTransactionVoidPolicy(s.card(), cadeiaDeOutroCartao(), d("2026-10-20")))
                .isInstanceOf(InvoiceChainInconsistentException.class);
        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(compra,
                VoidContext.of("x", NOW, d("2026-10-20")).withCard(s.card(), cadeiaDeOutroCartao())))
                .isInstanceOf(InvoiceChainInconsistentException.class);
        assertThat(compra.status()).isEqualTo(TransactionStatus.ACTIVE);
    }

    @Test
    @DisplayName("item ou pagamento com data fora do calendário quebra a invariante (DomainException), e a consulta também")
    void deveRecusarItemForaDoCalendario() {
        InvoiceScenario s = InvoiceScenario.comCompraParcelada();
        InvoiceCalendar calendar = s.card().calendar();
        InvoiceItem orfao = new InvoiceItem(PURCHASE_6X_ID, null, InvoiceItemKind.CREDIT, Money.of("1.00"), d("2026-10-10"),
                d("2026-11-24"), false, true);
        assertThatThrownBy(() -> new InvoiceChain(s.card().id(), calendar, List.of(orfao), List.of()))
                .isInstanceOf(InvoiceChainInconsistentException.class)
                .isNotInstanceOf(BusinessRuleException.class);
        InvoicePayment pagamentoOrfao = new InvoicePayment(PURCHASE_6X_ID, d("2026-10-24"), Money.of("1.00"), d("2026-10-24"), true);
        assertThatThrownBy(() -> new InvoiceChain(s.card().id(), calendar, List.of(), List.of(pagamentoOrfao)))
                .isInstanceOf(InvoiceChainInconsistentException.class);
        assertThatThrownBy(() -> s.chain().invoice(d("2026-10-24"))).isInstanceOf(InvoiceChainInconsistentException.class);
        assertThat(s.chain().cardId()).isEqualTo(s.card().id());
        assertThat(s.chain().hasClosingDate(NOV_CLOSING)).isTrue();
        assertThat(s.chain().hasClosingDate(d("2026-10-24"))).isFalse();
    }
}
