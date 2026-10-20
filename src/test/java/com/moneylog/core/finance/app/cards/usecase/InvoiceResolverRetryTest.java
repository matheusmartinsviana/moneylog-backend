package com.moneylog.core.finance.app.cards.usecase;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.app.gateways.InvoiceDocumentGateway;
import com.moneylog.core.card.app.gateways.InvoiceLookupGateway;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.exception.CardExceptions.InvoiceChainInconsistentException;
import com.moneylog.core.card.exception.CardExceptions.InvoiceResolutionCollidedException;
import com.moneylog.core.finance.app.cards.InvoiceDetails;
import com.moneylog.core.finance.app.cards.usecase.ChangeInvoiceDocumentAmountUseCase.ChangeInvoiceDocumentAmountCommand;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.TimeProvider;

@DisplayName("Resolução de fatura sob trava: retry e ordem")
class InvoiceResolverRetryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final LocalDate CLOSING = LocalDate.of(2026, 10, 25);

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private final InvoiceLookupGateway lookup = mock(InvoiceLookupGateway.class);
    private final CardGateway cards = mock(CardGateway.class);
    private final FinancialRecordsGateway records = mock(FinancialRecordsGateway.class);
    private final InvoiceDocumentGateway documents = mock(InvoiceDocumentGateway.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final UUID cardId = scenario.roxinho.id();
    private final UUID invoiceId = InvoiceIds.of(cardId, CLOSING);

    @BeforeEach
    void setUp() {
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();
        when(time.today()).thenReturn(TODAY);
        when(time.now()).thenReturn(Instant.parse("2026-11-05T15:00:00Z"));
        when(lookup.findById(USER_ID, invoiceId)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("uma falha de cadeia na descoberta do cartão: descarta o estado lido e a segunda tentativa passa")
    void umaFalhaPassa() {
        when(records.load(USER_ID)).thenThrow(new InvoiceChainInconsistentException("instável")).thenAnswer(call -> scenario.records());

        InvoiceResolver.Locked locked = InvoiceResolver.resolveLocked(USER_ID, invoiceId, lookup, cards, records, TODAY);

        assertThat(locked.resolved().card().id()).isEqualTo(cardId);
        assertThat(locked.resolved().closingDate()).isEqualTo(CLOSING);
        InOrder order = inOrder(records, cards);
        order.verify(records).load(USER_ID);
        order.verify(cards).discardReadState();
        order.verify(records).load(USER_ID);
        order.verify(cards).lockForUpdate(USER_ID, cardId);
        order.verify(cards).discardReadState();
        order.verify(records).load(USER_ID);
    }

    @Test
    @DisplayName("três falhas seguidas e a quarta passa")
    void tresFalhasPassam() {
        InvoiceChainInconsistentException falha = new InvoiceChainInconsistentException("instável");
        when(records.load(USER_ID)).thenThrow(falha, falha, falha).thenAnswer(call -> scenario.records());

        InvoiceResolver.Locked locked = InvoiceResolver.resolveLocked(USER_ID, invoiceId, lookup, cards, records, TODAY);

        assertThat(locked.resolved().card().id()).isEqualTo(cardId);
        verify(cards, times(3 + 2)).discardReadState();
    }

    @Test
    @DisplayName("quatro falhas: lança InvoiceResolutionCollidedException (409) com a causa, sem travar o cartão")
    void quatroFalhasEstouram() {
        when(records.load(USER_ID)).thenThrow(new InvoiceChainInconsistentException("instável"));

        assertThatThrownBy(() -> InvoiceResolver.resolveLocked(USER_ID, invoiceId, lookup, cards, records, TODAY))
                .isInstanceOf(InvoiceResolutionCollidedException.class)
                .hasCauseInstanceOf(InvoiceChainInconsistentException.class);
        verify(records, times(4)).load(USER_ID);
        verify(cards, times(3)).discardReadState();
        verify(cards, times(0)).lockForUpdate(USER_ID, cardId);
    }

    @Test
    @DisplayName("ChangeInvoiceDocumentAmount: trava o cartão, descarta o estado lido e só então relê os registros e grava o valor")
    void alterarValorDoDocumentoTravaAntesDeLer() {
        when(lookup.findById(USER_ID, invoiceId)).thenReturn(Optional.of(new InvoiceLookupGateway.InvoiceKey(cardId, CLOSING)));
        when(records.load(USER_ID)).thenAnswer(call -> scenario.records());
        when(documents.find(USER_ID, invoiceId)).thenReturn(new InvoiceDocumentGateway.Stored(null, 0));
        when(documents.change(USER_ID, scenario.roxinho, CLOSING, Money.of("1283.00")))
                .thenReturn(new InvoiceDocumentGateway.Stored(Money.of("1283.00"), 1));
        ChangeInvoiceDocumentAmountUseCaseImpl useCase = new ChangeInvoiceDocumentAmountUseCaseImpl(lookup, cards, records, documents, time);

        InvoiceDetails details = useCase.handle(new ChangeInvoiceDocumentAmountCommand(USER_ID, invoiceId, Money.of("1283.00"), null));

        assertThat(details.invoice().id()).isEqualTo(invoiceId);
        InOrder order = inOrder(cards, records, documents);
        order.verify(cards).lockForUpdate(USER_ID, cardId);
        order.verify(cards).discardReadState();
        order.verify(records).load(USER_ID);
        order.verify(documents).change(USER_ID, scenario.roxinho, CLOSING, Money.of("1283.00"));
    }
}
