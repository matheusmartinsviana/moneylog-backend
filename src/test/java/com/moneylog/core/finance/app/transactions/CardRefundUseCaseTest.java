package com.moneylog.core.finance.app.transactions;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.card.exception.CardExceptions.CardArchivedException;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Refund;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCaseImpl;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundExceedsOriginalException;
import com.moneylog.core.transaction.exception.TransactionExceptions.InvalidRefundOriginException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Casos de uso: reembolso/crédito no cartão (T56)")
class CardRefundUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final UUID TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000e0056");
    private static final UUID CARD_ID = CreditCardTestFactory.ROXINHO_ID;
    private static final LocalDate NOV_CLOSING = LocalDate.of(2026, 10, 25);

    @Mock
    private TransactionGateway transactions;
    @Mock
    private AccountGateway accounts;
    @Mock
    private CategoryGateway categories;
    @Mock
    private CardGateway cards;
    @Mock
    private LedgerWriteGateway ledger;
    @Mock
    private IdGenerator ids;
    @Mock
    private IdempotencyGateway idempotency;
    @Mock
    private TimeProvider time;

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private CreateTransactionUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        when(time.today()).thenReturn(TODAY);
        when(time.now()).thenReturn(NOW);
        when(ids.next()).thenReturn(TX_ID);
        when(categories.findById(USER_ID, scenario.alimentacao.id())).thenReturn(Optional.of(scenario.alimentacao));
        when(cards.findById(USER_ID, CARD_ID)).thenReturn(Optional.of(new Persisted<>(scenario.roxinho, NOW, NOW)));
        when(transactions.findAllOfCard(USER_ID, CARD_ID)).thenAnswer(call -> new ArrayList<>(scenario.transactions));
        when(transactions.sumActiveRefundsOf(any(), any())).thenReturn(Money.of("0.00"));
        when(transactions.insert(any())).thenAnswer(call -> {
            Transaction inserted = call.getArgument(0);
            scenario.transactions.add(inserted);
            return new Persisted<>(inserted, NOW, NOW);
        });
        var factory = new AccountTransactionFactory(transactions, accounts, categories, cards, time);
        useCase = new CreateTransactionUseCaseImpl(transactions, factory, ledger, ids,
                new TransactionDetailsAssembler(transactions, accounts, categories, cards, time), idempotency, time);
    }

    private TransactionDetails refund(String amount, LocalDate date, UUID original) {
        return useCase.handle(new Refund(new Common(USER_ID, Money.of(amount), date, "Estorno do banco", null), null,
                scenario.alimentacao.id(), original, CARD_ID));
    }

    private Transaction purchaseOf(String amount) {
        Transaction purchase = scenario.compraNoCartao(amount, 1);
        when(transactions.findById(USER_ID, purchase.id())).thenReturn(Optional.of(new Persisted<>(purchase, NOW, NOW)));
        return purchase;
    }

    private CreditCardInvoice invoice(LocalDate closing) {
        return scenario.chain().invoice(closing);
    }

    @Test
    @DisplayName("CA1: compra de 200,00 e reembolso de 50,00 na mesma categoria: consumo −50 na competência do reembolso, CARD +50, fatura alvo 150")
    void deveReembolsarUmaCompra() {
        Transaction purchase = purchaseOf("200.00");

        TransactionDetails details = refund("50.00", TODAY, purchase.id());

        Transaction saved = details.transaction();
        assertThat(saved.type()).isEqualTo(TransactionType.REFUND);
        assertThat(saved.cardId()).isEqualTo(CARD_ID);
        assertThat(saved.refundOfId()).isEqualTo(purchase.id());
        assertThat(saved.invoiceClosingDate()).isEqualTo(NOV_CLOSING);
        assertThat(saved.lateEntry()).isFalse();
        assertThat(details.impact().cash()).isEqualTo(Money.of("0.00"));
        assertThat(details.impact().consumption()).singleElement().satisfies(c -> {
            assertThat(c.competence().toString()).isEqualTo("2026-10");
            assertThat(c.amount()).isEqualTo(Money.of("-50.00"));
        });
        assertThat(details.cardInfo().invoice().id()).isEqualTo(InvoiceIds.of(CARD_ID, NOV_CLOSING));
        assertThat(details.flags().editable()).isFalse();
        assertThat(details.flags().voidable()).as("crédito em fatura OPEN é estornável (T57)").isTrue();
        ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(ledger).append(org.mockito.ArgumentMatchers.eq(USER_ID), entries.capture());
        assertThat(entries.getValue()).hasSize(2);
        assertThat(entries.getValue()).anySatisfy(e -> {
            assertThat(e.posting().bookType()).isEqualTo(BookType.CARD);
            assertThat(e.posting().amount()).isEqualTo(Money.of("50.00"));
        }).anySatisfy(e -> {
            assertThat(e.posting().bookType()).isEqualTo(BookType.EXPENSE);
            assertThat(e.posting().amount()).isEqualTo(Money.of("-50.00"));
        });
        assertThat(invoice(NOV_CLOSING).total()).isEqualTo(Money.of("150.00"));
    }

    @Test
    @DisplayName("CA2: crédito de 1.083,00 numa fatura OPEN de 200,00: aceito, total −883,00, outstanding 0,00 e creditCarry 883,00 que abate a seguinte")
    void deveAceitarCreditoMaiorQueAFatura() {
        scenario.compraNoCartao("200.00", 1);
        scenario.transactions.add(com.moneylog.core.transaction.util.factory.TransactionTestFactory.compraNoCalendario(
                scenario.header(LocalDate.of(2026, 11, 11), "Futura"), Money.of("100.00"), scenario.roxinho, scenario.alimentacao, 1));

        refund("1083.00", TODAY, null);

        CreditCardInvoice first = invoice(NOV_CLOSING);
        assertThat(first.total()).isEqualTo(Money.of("-883.00"));
        assertThat(first.outstanding()).isEqualTo(Money.of("0.00"));
        assertThat(first.creditCarry()).isEqualTo(Money.of("883.00"));
        CreditCardInvoice next = invoice(LocalDate.of(2026, 11, 25));
        assertThat(next.carryIn()).isEqualTo(Money.of("883.00"));
        assertThat(next.outstanding()).isEqualTo(Money.of("0.00"));
        assertThat(next.creditCarry()).isEqualTo(Money.of("783.00"));
        assertThat(scenario.chain().creditBalance()).isEqualTo(Money.of("783.00"));
    }

    @Test
    @DisplayName("CA3: reembolso acima do que resta é REFUND_EXCEEDS_ORIGINAL e outra categoria é REFUND_INVALID_ORIGINAL; nada gravado")
    void deveRespeitarOTetoELaCategoria() {
        Transaction purchase = purchaseOf("200.00");
        refund("150.00", TODAY, purchase.id());
        when(transactions.sumActiveRefundsOf(USER_ID, purchase.id())).thenReturn(Money.of("150.00"));
        org.mockito.Mockito.clearInvocations(transactions, ledger);

        assertThatThrownBy(() -> refund("60.00", TODAY, purchase.id())).isInstanceOf(RefundExceedsOriginalException.class);
        Category other = Category.create(UUID.fromString("00000000-0000-0000-0000-00000000c0c0"), USER_ID, "Outra",
                com.moneylog.core.category.domain.CategoryKind.EXPENSE);
        when(categories.findById(USER_ID, other.id())).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> useCase.handle(new Refund(new Common(USER_ID, Money.of("10.00"), TODAY, "x", null), null, other.id(),
                purchase.id(), CARD_ID))).isInstanceOf(InvalidRefundOriginException.class);

        verify(transactions, never()).insert(any());
        verify(ledger, never()).append(any(), any());
    }

    @Test
    @DisplayName("CA4: fatura de 25/10 CLOSED e não paga recebe o crédito como lançamento tardio; PAID manda o crédito para a corrente (25/11)")
    void deveAlocarOCreditoPeloStatusDaFatura() {
        scenario.compraNoCartao("200.00", 1);
        when(time.today()).thenReturn(LocalDate.of(2026, 10, 27));

        TransactionDetails late = refund("30.00", LocalDate.of(2026, 10, 20), null);

        assertThat(late.transaction().lateEntry()).isTrue();
        assertThat(late.transaction().invoiceClosingDate()).isEqualTo(NOV_CLOSING);
        assertThat(invoice(NOV_CLOSING).statusAt(LocalDate.of(2026, 10, 27))).isEqualTo(InvoiceStatus.CLOSED);

        scenario.pagarFatura("170.00", NOV_CLOSING, LocalDate.of(2026, 10, 27));
        TransactionDetails toCurrent = refund("20.00", LocalDate.of(2026, 10, 21), null);

        assertThat(toCurrent.transaction().lateEntry()).isTrue();
        assertThat(toCurrent.transaction().invoiceClosingDate()).isEqualTo(LocalDate.of(2026, 11, 25));
    }

    @Test
    @DisplayName("trava a despesa de origem, depois o cartão, antes de ler a cadeia; cartão arquivado é CARD_ARCHIVED sem gravar")
    void deveTravarNaOrdemGlobal() {
        Transaction purchase = purchaseOn();
        refund("10.00", TODAY, purchase.id());

        InOrder order = inOrder(transactions, cards);
        order.verify(transactions).lockForUpdate(USER_ID, purchase.id());
        order.verify(cards).lockForUpdate(USER_ID, CARD_ID);
        order.verify(cards).findById(USER_ID, CARD_ID);
        order.verify(transactions).findAllOfCard(USER_ID, CARD_ID);
        order.verify(transactions).insert(any());

        when(cards.findById(USER_ID, CARD_ID)).thenReturn(Optional.of(new Persisted<>(CreditCardTestFactory.umCartaoArquivado(), NOW, NOW)));
        org.mockito.Mockito.clearInvocations(transactions, ledger);
        assertThatThrownBy(() -> refund("10.00", TODAY, null)).isInstanceOf(CardArchivedException.class);
        verify(transactions, never()).insert(any());
        verify(ledger, never()).append(any(), any());
    }

    private Transaction purchaseOn() {
        return purchaseOf("100.00");
    }
}
