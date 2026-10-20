package com.moneylog.core.finance.app.cards;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.account.exception.AccountExceptions.AccountArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.app.gateways.InvoiceLookupGateway;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.card.exception.CardExceptions.InvalidInvoiceOperationException;
import com.moneylog.core.card.exception.CardExceptions.InvoiceNotFoundException;
import com.moneylog.core.finance.app.cards.PaymentPreviewView.Kind;
import com.moneylog.core.finance.app.cards.usecase.PayInvoiceUseCase.PayInvoiceCommand;
import com.moneylog.core.finance.app.cards.usecase.PayInvoiceUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.PreviewInvoicePaymentUseCase.PreviewInvoicePaymentQuery;
import com.moneylog.core.finance.app.cards.usecase.PreviewInvoicePaymentUseCaseImpl;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.AccountNotCashException;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;

@DisplayName("Pagamento de fatura e prévia (T55)")
class PayInvoiceUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-11-05T15:00:00Z");
    private static final LocalDate NOV_05 = LocalDate.of(2026, 11, 5);
    private static final LocalDate NOV_CLOSING = LocalDate.of(2026, 10, 25);
    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-0000000f0055");
    private static final UUID PAYMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000e0055");

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private final InvoiceLookupGateway lookup = mock(InvoiceLookupGateway.class);
    private final CardGateway cards = mock(CardGateway.class);
    private final AccountGateway accounts = mock(AccountGateway.class);
    private final TransactionGateway transactions = mock(TransactionGateway.class);
    private final LedgerWriteGateway ledger = mock(LedgerWriteGateway.class);
    private final FinancialRecordsGateway records = mock(FinancialRecordsGateway.class);
    private final IdempotencyGateway idempotency = mock(IdempotencyGateway.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final com.moneylog.core.card.app.gateways.InvoiceDocumentGateway documents = mock(com.moneylog.core.card.app.gateways.InvoiceDocumentGateway.class);
    private final UUID cardId = scenario.roxinho.id();
    private final UUID invoiceId = InvoiceIds.of(cardId, NOV_CLOSING);

    private PayInvoiceUseCaseImpl pay;
    private PreviewInvoicePaymentUseCaseImpl preview;

    @BeforeEach
    void setUp() {
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();
        when(time.today()).thenReturn(NOV_05);
        when(time.now()).thenReturn(NOW);
        when(ids.next()).thenReturn(PAYMENT_ID);
        when(records.load(USER_ID)).thenAnswer(call -> scenario.records());
        when(cards.findById(USER_ID, cardId)).thenReturn(Optional.of(new Persisted<>(scenario.roxinho, NOW, NOW)));
        when(accounts.findById(USER_ID, scenario.nubank.id())).thenReturn(Optional.of(new Persisted<>(scenario.nubank, NOW, NOW)));
        when(transactions.findAllOfCard(USER_ID, cardId)).thenAnswer(call -> List.copyOf(scenario.transactions));
        when(transactions.insert(any())).thenAnswer(call -> {
            Transaction inserted = call.getArgument(0);
            scenario.transactions.add(inserted);
            return new Persisted<>(inserted, NOW, NOW);
        });
        when(idempotency.claim(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(lookup.findById(USER_ID, invoiceId)).thenReturn(Optional.empty());
        when(documents.find(any(), any())).thenReturn(new com.moneylog.core.card.app.gateways.InvoiceDocumentGateway.Stored(null, 0));
        pay = new PayInvoiceUseCaseImpl(lookup, cards, accounts, transactions, ledger, records, idempotency, ids, time, documents);
        preview = new PreviewInvoicePaymentUseCaseImpl(lookup, records, ids, time);
    }

    private PayInvoiceCommand command(String amount) {
        return new PayInvoiceCommand(USER_ID, invoiceId, scenario.nubank.id(), Money.of(amount), NOV_05);
    }

    @Nested
    @DisplayName("pagar")
    class Pagar {

        @Test
        @DisplayName("1.283,00: PAID, postings CARD +1.283 / ACCOUNT −1.283 e nenhum EXPENSE; trava o cartão antes da conta, descarta o estado lido antes da trava (B1) e só então lê a cadeia")
        void devePagarATotalidade() {
            InvoiceDetails details = pay.handle(command("1283.00"), KEY);

            assertThat(details.invoice().status()).isEqualTo(InvoiceStatus.PAID);
            assertThat(details.invoice().paid()).isEqualTo(Money.of("1283.00"));
            assertThat(details.invoice().outstanding()).isEqualTo(Money.of("0.00"));
            assertThat(details.payments()).singleElement().satisfies(p -> {
                assertThat(p.transactionId()).isEqualTo(PAYMENT_ID);
                assertThat(p.accountId()).isEqualTo(scenario.nubank.id());
            });
            ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
            ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
            verify(transactions).insert(saved.capture());
            verify(ledger).append(org.mockito.ArgumentMatchers.eq(USER_ID), entries.capture());
            Transaction payment = saved.getValue();
            assertThat(payment.type()).isEqualTo(TransactionType.INVOICE_PAYMENT);
            assertThat(payment.invoiceClosingDate()).isEqualTo(NOV_CLOSING);
            assertThat(payment.description()).isEqualTo("Fatura Roxinho 2026-11");
            assertThat(entries.getValue()).hasSize(2).noneMatch(e -> e.posting().bookType() == BookType.EXPENSE);
            assertThat(entries.getValue()).anySatisfy(e -> {
                assertThat(e.posting().bookType()).isEqualTo(BookType.CARD);
                assertThat(e.posting().amount()).isEqualTo(Money.of("1283.00"));
            }).anySatisfy(e -> {
                assertThat(e.posting().bookType()).isEqualTo(BookType.ACCOUNT);
                assertThat(e.posting().amount()).isEqualTo(Money.of("-1283.00"));
            });
            InOrder order = inOrder(cards, accounts, transactions);
            order.verify(cards).lockForUpdate(USER_ID, cardId);
            order.verify(accounts).lockForUpdate(USER_ID, scenario.nubank.id());
            order.verify(cards).discardReadState();
            order.verify(cards).findById(USER_ID, cardId);
            order.verify(transactions).findAllOfCard(USER_ID, cardId);
            order.verify(transactions).insert(any());
        }

        @Test
        @DisplayName("fatura já materializada: trava o cartão antes de ler os registros, para não ler cartão e parcelas em estados diferentes")
        void deveTravarOCartaoAntesDeLerOsRegistros() {
            when(lookup.findById(USER_ID, invoiceId)).thenReturn(Optional.of(new InvoiceLookupGateway.InvoiceKey(cardId, NOV_CLOSING)));

            pay.handle(command("1283.00"), KEY);

            InOrder order = inOrder(cards, records);
            order.verify(cards).lockForUpdate(USER_ID, cardId);
            order.verify(cards).discardReadState();
            order.verify(records).load(USER_ID);
        }

        @Test
        @DisplayName("fatura ainda não materializada: relê os registros depois da trava do cartão")
        void deveRelerOsRegistrosDepoisDaTrava() {
            pay.handle(command("1283.00"), KEY);

            InOrder order = inOrder(cards, records);
            order.verify(records).load(USER_ID);
            order.verify(cards).lockForUpdate(USER_ID, cardId);
            order.verify(cards).discardReadState();
            order.verify(records).load(USER_ID);
        }

        @Test
        @DisplayName("500,00 e depois 783,00 em duas chamadas: OVERDUE com 783,00 em aberto, depois PAID")
        void devePagarEmDuasVezes() {
            when(time.today()).thenReturn(NOV_05.plusDays(1));
            when(ids.next()).thenReturn(PAYMENT_ID, UUID.fromString("00000000-0000-0000-0000-0000000e0056"));

            InvoiceDetails first = pay.handle(new PayInvoiceCommand(USER_ID, invoiceId, scenario.nubank.id(), Money.of("500.00"),
                    NOV_05.plusDays(1)), null);
            assertThat(first.invoice().status()).isEqualTo(InvoiceStatus.OVERDUE);
            assertThat(first.invoice().outstanding()).isEqualTo(Money.of("783.00"));

            InvoiceDetails second = pay.handle(new PayInvoiceCommand(USER_ID, invoiceId, scenario.nubank.id(), Money.of("783.00"),
                    NOV_05.plusDays(1)), null);
            assertThat(second.invoice().status()).isEqualTo(InvoiceStatus.PAID);
            assertThat(second.payments()).hasSize(2);
        }

        @Test
        @DisplayName("1.300,00 (a maior): aceito, creditCarry 17,00; 300,00 numa fatura OPEN de 200,00 em 20/10 (antecipação): aceito")
        void deveAceitarAMaiorEAAntecipacao() {
            InvoiceDetails over = pay.handle(command("1300.00"), null);
            assertThat(over.invoice().creditCarry()).isEqualTo(Money.of("17.00"));
            assertThat(over.invoice().status()).isEqualTo(InvoiceStatus.PAID);
        }

        @Test
        @DisplayName("antecipação: 300,00 na fatura OPEN dez/26 (200,00) em 20/10... fica com creditCarry 100,00")
        void deveAntecipar() {
            scenario.transactions.clear();
            scenario.transactions.add(com.moneylog.core.transaction.util.factory.TransactionTestFactory.compraNoCalendario(
                    com.moneylog.core.transaction.util.factory.TransactionTestFactory.umHeader(
                            UUID.fromString("00000000-0000-0000-0000-0000000e0099"), LocalDate.of(2026, 10, 10)),
                    Money.of("200.00"), scenario.roxinho, scenario.alimentacao, 1));
            when(time.today()).thenReturn(LocalDate.of(2026, 10, 20));

            InvoiceDetails details = pay.handle(new PayInvoiceCommand(USER_ID, invoiceId, scenario.nubank.id(), Money.of("300.00"),
                    LocalDate.of(2026, 10, 20)), null);

            assertThat(details.invoice().status()).isEqualTo(InvoiceStatus.OPEN);
            assertThat(details.invoice().creditCarry()).isEqualTo(Money.of("100.00"));
            assertThat(details.invoice().outstanding()).isEqualTo(Money.of("0.00"));
        }

        @Test
        @DisplayName("fatura FUTURE ou já PAID: INVOICE_NOT_PAYABLE e nada é gravado")
        void deveRecusarFaturaFutureOuPaga() {
            UUID future = InvoiceIds.of(cardId, LocalDate.of(2026, 12, 25));
            assertThatThrownBy(() -> pay.handle(new PayInvoiceCommand(USER_ID, future, scenario.nubank.id(), Money.of("10.00"), NOV_05), null))
                    .isInstanceOf(InvalidInvoiceOperationException.class).hasFieldOrPropertyWithValue("code", "INVOICE_NOT_PAYABLE");
            pay.handle(command("1283.00"), null);
            org.mockito.Mockito.clearInvocations(transactions, ledger);

            assertThatThrownBy(() -> pay.handle(command("10.00"), null)).isInstanceOf(InvalidInvoiceOperationException.class)
                    .hasFieldOrPropertyWithValue("code", "INVOICE_NOT_PAYABLE");
            verify(transactions, never()).insert(any());
            verify(ledger, never()).append(any(), anyList());
        }

        @Test
        @DisplayName("conta que não é CASH, arquivada ou de outro usuário; valor zero ou acima do teto: recusado, nada gravado")
        void deveRecusarContaEValorInvalidos() {
            Account savings = AccountTestFactory.umaContaDoTipo(AccountType.INVESTMENT);
            when(accounts.findById(USER_ID, savings.id())).thenReturn(Optional.of(new Persisted<>(savings, NOW, NOW)));
            Account archived = AccountTestFactory.umaContaArquivada();
            when(accounts.findById(USER_ID, archived.id())).thenReturn(Optional.of(new Persisted<>(archived, NOW, NOW)));

            assertThatThrownBy(() -> pay.handle(new PayInvoiceCommand(USER_ID, invoiceId, savings.id(), Money.of("10.00"), NOV_05), null))
                    .isInstanceOf(AccountNotCashException.class);
            assertThatThrownBy(() -> pay.handle(new PayInvoiceCommand(USER_ID, invoiceId, archived.id(), Money.of("10.00"), NOV_05), null))
                    .isInstanceOf(AccountArchivedException.class);
            assertThatThrownBy(() -> pay.handle(new PayInvoiceCommand(USER_ID, invoiceId, UUID.randomUUID(), Money.of("10.00"), NOV_05), null))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThatThrownBy(() -> pay.handle(command("0.00"), null)).isInstanceOf(DomainException.class)
                    .hasFieldOrPropertyWithValue("code", "TRANSACTION_AMOUNT_NOT_POSITIVE");
            assertThatThrownBy(() -> pay.handle(command("1000000000.00"), null)).isInstanceOf(DomainException.class)
                    .hasFieldOrPropertyWithValue("code", "AMOUNT_ABOVE_LIMIT");
            verify(transactions, never()).insert(any());
            verify(ledger, never()).append(any(), anyList());
        }

        @Test
        @DisplayName("fatura de outro usuário ou que não resolve: INVOICE_NOT_FOUND, sem travar nada")
        void deveRecusarFaturaAlheia() {
            assertThatThrownBy(() -> pay.handle(new PayInvoiceCommand(USER_ID, UUID.randomUUID(), scenario.nubank.id(), Money.of("1.00"),
                    NOV_05), null)).isInstanceOf(InvoiceNotFoundException.class);
            verify(cards, never()).lockForUpdate(any(), any());
            verify(accounts, never()).lockForUpdate(any(), any());
        }
    }

    @Nested
    @DisplayName("idempotência")
    class Idempotencia {

        @Test
        @DisplayName("mesma chave e mesmo corpo: devolve a fatura atual sem segundo pagamento")
        void deveRepetirSemPagarDeNovo() {
            pay.handle(command("500.00"), KEY);
            Transaction first = scenario.transactions.getLast();
            org.mockito.Mockito.clearInvocations(transactions, ledger, cards, accounts);
            String hash = com.moneylog.core.finance.app.transactions.CanonicalCommandHash.ofInvoicePayment(invoiceId,
                    scenario.nubank.id(), Money.of("500.00"), NOV_05);
            when(idempotency.claim(any(), any(), any(), any(), any(), any(), any())).thenReturn(false);
            when(idempotency.find(USER_ID, PayInvoiceUseCaseImpl.IDEMPOTENCY_SCOPE, KEY))
                    .thenReturn(Optional.of(new Claim(hash, first.id(), NOW)));
            when(transactions.findById(USER_ID, first.id())).thenReturn(Optional.of(new Persisted<>(first, NOW, NOW)));

            InvoiceDetails again = pay.handle(command("500.00"), KEY);

            assertThat(again.invoice().paid()).isEqualTo(Money.of("500.00"));
            assertThat(again.payments()).hasSize(1);
            verify(transactions, never()).insert(any());
            verifyNoInteractions(ledger);
            verify(cards, never()).lockForUpdate(any(), any());
        }

        @Test
        @DisplayName("mesma chave com outro valor: IDEMPOTENCY_KEY_REUSED; o escopo da chave é o do pagamento")
        void deveRecusarOutroCorpo() {
            String hash = com.moneylog.core.finance.app.transactions.CanonicalCommandHash.ofInvoicePayment(invoiceId,
                    scenario.nubank.id(), Money.of("500.00"), NOV_05);
            when(idempotency.claim(any(), any(), any(), any(), any(), any(), any())).thenReturn(false);
            when(idempotency.find(USER_ID, PayInvoiceUseCaseImpl.IDEMPOTENCY_SCOPE, KEY)).thenReturn(Optional.of(new Claim(hash, UUID.randomUUID(), NOW)));

            assertThatThrownBy(() -> pay.handle(command("501.00"), KEY)).isInstanceOf(IdempotencyKeyReusedException.class);
            verify(transactions, never()).insert(any());
        }
    }

    @Nested
    @DisplayName("prévia")
    class Previa {

        private com.moneylog.core.finance.app.cards.PaymentPreviewView preview(String amount) {
            return preview.handle(new PreviewInvoicePaymentQuery(USER_ID, invoiceId, Money.of(amount)));
        }

        @Test
        @DisplayName("500,00 PARTIAL (783,00 / 0,00); 1.283,00 FULL; 1.300,00 OVERPAY (0,00 / 17,00) na fatura CLOSED")
        void deveClassificarOPagamento() {
            when(time.today()).thenReturn(LocalDate.of(2026, 10, 30));

            assertThat(preview("500.00")).satisfies(p -> {
                assertThat(p.kind()).isEqualTo(Kind.PARTIAL);
                assertThat(p.outstandingAfter()).isEqualTo(Money.of("783.00"));
                assertThat(p.creditCarryAfter()).isEqualTo(Money.of("0.00"));
            });
            assertThat(preview("1283.00").kind()).isEqualTo(Kind.FULL);
            assertThat(preview("1300.00")).satisfies(p -> {
                assertThat(p.kind()).isEqualTo(Kind.OVERPAY);
                assertThat(p.outstandingAfter()).isEqualTo(Money.of("0.00"));
                assertThat(p.creditCarryAfter()).isEqualTo(Money.of("17.00"));
            });
        }

        @Test
        @DisplayName("fatura OPEN: qualquer pagamento é PREPAY; FUTURE e PAID: INVOICE_NOT_PAYABLE")
        void deveClassificarAntecipacaoERecusarFutureEPaga() {
            when(time.today()).thenReturn(LocalDate.of(2026, 10, 20));
            assertThat(preview("2000.00")).satisfies(p -> {
                assertThat(p.kind()).isEqualTo(Kind.PREPAY);
                assertThat(p.creditCarryAfter()).isEqualTo(Money.of("717.00"));
            });
            assertThat(preview("100.00").kind()).isEqualTo(Kind.PREPAY);
            UUID future = InvoiceIds.of(cardId, LocalDate.of(2026, 11, 25));
            assertThatThrownBy(() -> preview.handle(new PreviewInvoicePaymentQuery(USER_ID, future, Money.of("1.00"))))
                    .isInstanceOf(InvalidInvoiceOperationException.class);
            when(time.today()).thenReturn(NOV_05);
            scenario.pagarFatura("1283.00", NOV_CLOSING, NOV_05);
            assertThatThrownBy(() -> preview("1.00")).isInstanceOf(InvalidInvoiceOperationException.class)
                    .hasFieldOrPropertyWithValue("code", "INVOICE_NOT_PAYABLE");
        }

        @Test
        @DisplayName("os números da prévia são os do pagamento real e a prévia não grava nem trava nada")
        void deveSerIgualAoPagamentoReal() {
            when(time.today()).thenReturn(LocalDate.of(2026, 10, 30));
            var predicted = preview("1300.00");
            verify(transactions, never()).insert(any());
            verifyNoInteractions(ledger);
            verify(cards, never()).lockForUpdate(any(), any());

            InvoiceDetails real = pay.handle(new PayInvoiceCommand(USER_ID, invoiceId, scenario.nubank.id(), Money.of("1300.00"),
                    LocalDate.of(2026, 10, 30)), null);

            assertThat(real.invoice().outstanding()).isEqualTo(predicted.outstandingAfter());
            assertThat(real.invoice().creditCarry()).isEqualTo(predicted.creditCarryAfter());
        }
    }
}
