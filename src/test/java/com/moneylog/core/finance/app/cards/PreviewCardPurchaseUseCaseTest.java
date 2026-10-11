package com.moneylog.core.finance.app.cards;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.card.exception.CardExceptions.CardArchivedException;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.finance.app.cards.PurchasePreviewView.Warning;
import com.moneylog.core.finance.app.cards.usecase.PreviewCardPurchaseUseCase.PreviewCardPurchaseQuery;
import com.moneylog.core.finance.app.cards.usecase.PreviewCardPurchaseUseCaseImpl;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;

@DisplayName("Prévia de compra no cartão (T53 CA4)")
class PreviewCardPurchaseUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final UUID CARD_ID = CreditCardTestFactory.ROXINHO_ID;

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private CardGateway cards;
    private FinancialRecordsGateway records;
    private TimeProvider time;
    private IdGenerator ids;
    private PreviewCardPurchaseUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        cards = mock(CardGateway.class);
        records = mock(FinancialRecordsGateway.class);
        time = mock(TimeProvider.class);
        ids = mock(IdGenerator.class);
        when(time.today()).thenReturn(TODAY);
        when(ids.next()).thenReturn(UUID.fromString("00000000-0000-0000-0000-0000000e0001"), UUID.fromString("00000000-0000-0000-0000-0000000e0002"));
        when(cards.findById(USER_ID, CARD_ID)).thenReturn(Optional.of(new Persisted<>(scenario.roxinho, NOW, NOW)));
        when(records.load(USER_ID)).thenAnswer(call -> scenario.records());
        useCase = new PreviewCardPurchaseUseCaseImpl(cards, records, ids, time);
    }

    private PurchasePreviewView preview(String amount, int installments, LocalDate date) {
        return useCase.handle(new PreviewCardPurchaseQuery(USER_ID, CARD_ID, Money.of(amount), installments, date));
    }

    @Test
    @DisplayName("1.200 em 6x em 10/10: 6 parcelas de 200, faturas 25/10..25/03, limite depois 8.800, sem avisos")
    void devePrevisualizarACompraParcelada() {
        PurchasePreviewView view = preview("1200.00", 6, TODAY);

        assertThat(view.total()).isEqualTo(Money.of("1200.00"));
        assertThat(view.availableLimitAfter()).isEqualTo(Money.of("8800.00"));
        assertThat(view.overLimit()).isFalse();
        assertThat(view.warnings()).isEmpty();
        assertThat(view.installments()).hasSize(6);
        PurchasePreviewView.Installment first = view.installments().getFirst();
        assertThat(first.number()).isEqualTo(1);
        assertThat(first.total()).isEqualTo(6);
        assertThat(first.amount()).isEqualTo(Money.of("200.00"));
        assertThat(first.competence().toString()).isEqualTo("2026-10");
        assertThat(first.lateEntry()).isFalse();
        assertThat(first.invoice().id()).isEqualTo(InvoiceIds.of(CARD_ID, LocalDate.of(2026, 10, 25)));
        assertThat(first.invoice().referenceMonth().toString()).isEqualTo("2026-11");
        assertThat(first.invoice().closingDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(first.invoice().dueDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(first.invoice().status()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(view.installments().get(1).invoice().status()).isEqualTo(InvoiceStatus.FUTURE);
    }

    @Test
    @DisplayName("é igual ao que a criação grava: parcelas, faturas e lançamento tardio idênticos (mesma fábrica do domínio)")
    void deveSerIgualACriacao() {
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();
        PurchasePreviewView view = preview("1000.00", 3, TODAY);

        Transaction created = CardTransactions.purchase(TransactionHeader.of(UUID.randomUUID(), USER_ID, TODAY, "x"),
                Money.of("1000.00"), scenario.roxinho, scenario.alimentacao, 3, CardInvoices.chainFor(scenario.roxinho, scenario.transactions), TODAY);

        assertThat(view.installments().stream().map(i -> i.amount().toPlainString()).toList()).containsExactly("333.34", "333.33", "333.33");
        for (int k = 0; k < 3; k++) {
            assertThat(view.installments().get(k).invoice().closingDate()).isEqualTo(created.installments().get(k).invoiceClosingDate());
            assertThat(view.installments().get(k).amount()).isEqualTo(created.installments().get(k).amount());
            assertThat(view.installments().get(k).lateEntry()).isEqualTo(created.installments().get(k).lateEntry());
            assertThat(view.installments().get(k).competence()).isEqualTo(created.installments().get(k).competence());
        }
    }

    @Test
    @DisplayName("compra acima do limite é aceita e sinalizada: overLimit e OVER_LIMIT")
    void deveSinalizarAcimaDoLimite() {
        PurchasePreviewView view = preview("12000.00", 1, TODAY);

        assertThat(view.overLimit()).isTrue();
        assertThat(view.availableLimitAfter()).isEqualTo(Money.of("-2000.00"));
        assertThat(view.warnings()).containsExactly(Warning.OVER_LIMIT);
    }

    @Test
    @DisplayName("o limite depois considera o que já foi comprado (1.283 em uso: 10.000 − 1.283 − 500 = 8.217)")
    void deveConsiderarOUsoAtual() {
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();

        assertThat(preview("500.00", 1, TODAY).availableLimitAfter()).isEqualTo(Money.of("7217.00"));
    }

    @Test
    @DisplayName("compra em data futura traz FUTURE_DATE; compra tardia em fatura fechada traz LATE_ENTRY")
    void deveAvisar() {
        assertThat(preview("100.00", 1, LocalDate.of(2026, 10, 20)).warnings()).containsExactly(Warning.FUTURE_DATE);

        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();
        when(time.today()).thenReturn(LocalDate.of(2026, 10, 27));
        PurchasePreviewView late = preview("90.00", 1, LocalDate.of(2026, 10, 20));

        assertThat(late.warnings()).containsExactly(Warning.LATE_ENTRY);
        assertThat(late.installments().getFirst().lateEntry()).isTrue();
        assertThat(late.installments().getFirst().invoice().status()).isEqualTo(InvoiceStatus.CLOSED);
        assertThat(late.installments().getFirst().invoice().closingDate()).isEqualTo(LocalDate.of(2026, 10, 25));
    }

    @Test
    @DisplayName("cartão arquivado é CARD_ARCHIVED; inexistente ou de outro usuário é CARD_NOT_FOUND")
    void deveRecusarCartaoInvalido() {
        CreditCard archived = CreditCardTestFactory.umCartaoArquivado();
        when(cards.findById(USER_ID, CARD_ID)).thenReturn(Optional.of(new Persisted<>(archived, NOW, NOW)));
        assertThatThrownBy(() -> preview("10.00", 1, TODAY)).isInstanceOf(CardArchivedException.class);

        when(cards.findById(USER_ID, CARD_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> preview("10.00", 1, TODAY)).isInstanceOf(CardNotFoundException.class);
    }

    @Test
    @DisplayName("não grava nada: só lê o cartão e os registros")
    void naoDeveGravar() {
        preview("100.00", 2, TODAY);

        verify(cards).findById(USER_ID, CARD_ID);
        verify(records).load(USER_ID);
        verifyNoMoreInteractions(cards, records);
        assertThat(scenario.transactions).isEmpty();
    }
}
