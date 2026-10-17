package com.moneylog.core.finance.app.cards;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.moneylog.core.card.app.gateways.InvoiceLookupGateway;
import com.moneylog.core.card.app.gateways.InvoiceLookupGateway.InvoiceKey;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.domain.InvoiceItemKind;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.card.exception.CardExceptions.InvoiceNotFoundException;
import com.moneylog.core.finance.app.cards.usecase.GetFutureInstallmentsUseCase.GetFutureInstallmentsQuery;
import com.moneylog.core.finance.app.cards.usecase.GetFutureInstallmentsUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.GetInvoiceUseCase.GetInvoiceQuery;
import com.moneylog.core.finance.app.cards.usecase.GetInvoiceUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.ListInvoicesUseCase.ListInvoicesQuery;
import com.moneylog.core.finance.app.cards.usecase.ListInvoicesUseCaseImpl;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.time.TimeProvider;

@DisplayName("Casos de uso de leitura de faturas (T54)")
class InvoiceReadUseCasesTest {

    private static final LocalDate OCT_10 = LocalDate.of(2026, 10, 10);
    private static final LocalDate OCT_30 = LocalDate.of(2026, 10, 30);
    private static final LocalDate NOV_CLOSING = LocalDate.of(2026, 10, 25);

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private final FinancialRecordsGateway records = mock(FinancialRecordsGateway.class);
    private final InvoiceLookupGateway lookup = mock(InvoiceLookupGateway.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final UUID cardId = scenario.roxinho.id();

    @BeforeEach
    void setUp() {
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();
        when(records.load(USER_ID)).thenAnswer(call -> scenario.records());
        when(time.today()).thenReturn(OCT_10);
        when(lookup.findById(USER_ID, InvoiceIds.of(cardId, NOV_CLOSING))).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("lista de faturas")
    class Lista {

        private final ListInvoicesUseCaseImpl useCase = new ListInvoicesUseCaseImpl(records, time);

        @Test
        @DisplayName("janela padrão em 10/10: da fatura com item até a última FUTURE com item, em ordem de fechamento decrescente")
        void deveListarAJanelaPadrao() {
            var slice = useCase.handle(new ListInvoicesQuery(USER_ID, cardId, null, null, new PageQuery(1, 50)));

            assertThat(slice.items()).extracting(i -> i.closingDate().toString())
                    .containsExactly("2027-03-25", "2027-02-25", "2027-01-25", "2026-12-25", "2026-11-25", "2026-10-25");
            assertThat(slice.items().getLast().status()).isEqualTo(InvoiceStatus.OPEN);
            assertThat(slice.items().getLast().total()).isEqualTo(Money.of("1283.00"));
            assertThat(slice.items().getFirst().status()).isEqualTo(InvoiceStatus.FUTURE);
            assertThat(slice.items().getFirst().id()).isEqualTo(InvoiceIds.of(cardId, LocalDate.of(2027, 3, 25)));
            assertThat(slice.hasNext()).isFalse();
        }

        @Test
        @DisplayName("pagina: pageSize 2 devolve as duas mais distantes e hasNext; a última página não tem próxima")
        void devePaginar() {
            var first = useCase.handle(new ListInvoicesQuery(USER_ID, cardId, null, null, new PageQuery(1, 2)));
            var last = useCase.handle(new ListInvoicesQuery(USER_ID, cardId, null, null, new PageQuery(3, 2)));
            var beyond = useCase.handle(new ListInvoicesQuery(USER_ID, cardId, null, null, new PageQuery(4, 2)));

            assertThat(first.items()).hasSize(2);
            assertThat(first.hasNext()).isTrue();
            assertThat(last.items()).extracting(i -> i.closingDate().toString()).containsExactly("2026-11-25", "2026-10-25");
            assertThat(last.hasNext()).isFalse();
            assertThat(beyond.items()).isEmpty();
        }

        @Test
        @DisplayName("cartão sem movimento: só a fatura corrente (vazia, com o id determinístico)")
        void deveListarSoACorrenteSeNaoHaItens() {
            scenario.transactions.clear();

            var slice = useCase.handle(new ListInvoicesQuery(USER_ID, cardId, null, null, new PageQuery(1, 10)));

            assertThat(slice.items()).hasSize(1);
            assertThat(slice.items().getFirst().id()).isEqualTo(InvoiceIds.of(cardId, NOV_CLOSING));
            assertThat(slice.items().getFirst().total()).isEqualTo(Money.of("0.00"));
        }

        @Test
        @DisplayName("from/to filtram por closingDate (inclusive) e trazem as faturas vazias do calendário dentro da janela do id")
        void deveFiltrarPorFechamento() {
            var slice = useCase.handle(new ListInvoicesQuery(USER_ID, cardId, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 25),
                    new PageQuery(1, 50)));

            assertThat(slice.items()).extracting(i -> i.closingDate().toString())
                    .containsExactly("2026-11-25", "2026-10-25", "2026-09-25");
            assertThat(slice.items().getLast().total()).isEqualTo(Money.of("0.00"));
        }

        @Test
        @DisplayName("cartão inexistente ou de outro usuário: CARD_NOT_FOUND")
        void deveRecusarCartaoAlheio() {
            assertThatThrownBy(() -> useCase.handle(new ListInvoicesQuery(USER_ID, UUID.randomUUID(), null, null, PageQuery.first())))
                    .isInstanceOf(CardNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("detalhe da fatura")
    class Detalhe {

        private final GetInvoiceUseCaseImpl useCase = new GetInvoiceUseCaseImpl(lookup, records, time, documentsStub());

        @Test
        @DisplayName("em 30/10 a fatura de 25/10 está CLOSED com a parcela 1/6 'Notebook' e a compra à vista, sem pagamentos")
        void deveDetalharAFaturaFechada() {
            when(time.today()).thenReturn(OCT_30);

            InvoiceDetails details = useCase.handle(new GetInvoiceQuery(USER_ID, InvoiceIds.of(cardId, NOV_CLOSING)));

            assertThat(details.invoice().status()).isEqualTo(InvoiceStatus.CLOSED);
            assertThat(details.invoice().total()).isEqualTo(Money.of("1283.00"));
            assertThat(details.items()).extracting(InvoiceDetails.Item::description).containsExactly("Notebook", "Mercado");
            InvoiceDetails.Item installment = details.items().getFirst();
            assertThat(installment.kind()).isEqualTo(InvoiceItemKind.INSTALLMENT);
            assertThat(installment.number()).isEqualTo(1);
            assertThat(installment.totalInstallments()).isEqualTo(6);
            assertThat(installment.amount()).isEqualTo(Money.of("200.00"));
            assertThat(installment.lateEntry()).isFalse();
            assertThat(details.items().get(1).number()).isEqualTo(1);
            assertThat(details.payments()).isEmpty();
        }

        @Test
        @DisplayName("fatura paga: o pagamento aparece com a conta e o valor; estornado, some")
        void deveMostrarOPagamentoAtivo() {
            var payment = scenario.pagarFatura("500.00", NOV_CLOSING, LocalDate.of(2026, 11, 5));
            when(time.today()).thenReturn(LocalDate.of(2026, 11, 6));

            InvoiceDetails details = useCase.handle(new GetInvoiceQuery(USER_ID, InvoiceIds.of(cardId, NOV_CLOSING)));

            assertThat(details.invoice().status()).isEqualTo(InvoiceStatus.OVERDUE);
            assertThat(details.invoice().paid()).isEqualTo(Money.of("500.00"));
            assertThat(details.invoice().outstanding()).isEqualTo(Money.of("783.00"));
            assertThat(details.payments()).singleElement().satisfies(p -> {
                assertThat(p.transactionId()).isEqualTo(payment.id());
                assertThat(p.accountId()).isEqualTo(scenario.nubank.id());
                assertThat(p.amount()).isEqualTo(Money.of("500.00"));
            });
        }

        @Test
        @DisplayName("fatura sem linha (a seguinte, só com a parcela 2/6): resolvida pela janela do calendário, não pela linha")
        void deveResolverSemLinha() {
            LocalDate closing = LocalDate.of(2026, 11, 25);

            InvoiceDetails details = useCase.handle(new GetInvoiceQuery(USER_ID, InvoiceIds.of(cardId, closing)));

            assertThat(details.invoice().closingDate()).isEqualTo(closing);
            assertThat(details.invoice().status()).isEqualTo(InvoiceStatus.FUTURE);
            assertThat(details.items()).singleElement().satisfies(i -> assertThat(i.number()).isEqualTo(2));
        }

        @Test
        @DisplayName("com linha gravada, o par (cartão, fechamento) vem da linha, mesmo fora da janela de 24 meses")
        void deveUsarALinhaGravada() {
            LocalDate old = LocalDate.of(2020, 1, 25);
            UUID id = InvoiceIds.of(cardId, old);
            when(lookup.findById(USER_ID, id)).thenReturn(Optional.of(new InvoiceKey(cardId, old)));

            InvoiceDetails details = useCase.handle(new GetInvoiceQuery(USER_ID, id));

            assertThat(details.invoice().id()).isEqualTo(id);
            assertThat(details.invoice().total()).isEqualTo(Money.of("0.00"));
            verify(lookup).findById(USER_ID, id);
        }

        @Test
        @DisplayName("id desconhecido, de outro cartão ou fora da janela [−24, +36 meses]: INVOICE_NOT_FOUND")
        void deveRecusarIdQueNaoResolve() {
            assertThatThrownBy(() -> useCase.handle(new GetInvoiceQuery(USER_ID, UUID.randomUUID())))
                    .isInstanceOf(InvoiceNotFoundException.class);
            assertThatThrownBy(() -> useCase.handle(new GetInvoiceQuery(USER_ID, InvoiceIds.of(UUID.randomUUID(), NOV_CLOSING))))
                    .isInstanceOf(InvoiceNotFoundException.class);
            assertThatThrownBy(() -> useCase.handle(new GetInvoiceQuery(USER_ID, InvoiceIds.of(cardId, LocalDate.of(2024, 9, 25)))))
                    .isInstanceOf(InvoiceNotFoundException.class);
            assertThatThrownBy(() -> useCase.handle(new GetInvoiceQuery(USER_ID, InvoiceIds.of(cardId, LocalDate.of(2029, 12, 25)))))
                    .isInstanceOf(InvoiceNotFoundException.class);
        }

        @Test
        @DisplayName("os limites da janela resolvem: 24 meses antes (10/2024) e 36 meses depois (10/2029)")
        void deveResolverNosLimitesDaJanela() {
            assertThat(useCase.handle(new GetInvoiceQuery(USER_ID, InvoiceIds.of(cardId, LocalDate.of(2024, 10, 25)))).invoice()
                    .closingDate()).isEqualTo(LocalDate.of(2024, 10, 25));
            assertThat(useCase.handle(new GetInvoiceQuery(USER_ID, InvoiceIds.of(cardId, LocalDate.of(2029, 10, 25)))).invoice()
                    .closingDate()).isEqualTo(LocalDate.of(2029, 10, 25));
        }
    }

    @Nested
    @DisplayName("parcelas futuras")
    class Futuras {

        private final GetFutureInstallmentsUseCaseImpl useCase = new GetFutureInstallmentsUseCaseImpl(records, time);

        @Test
        @DisplayName("total 1.000,00 em 5 faturas (2026-12 a 2027-04), cada uma com a parcela 'Notebook k/6', e a soma é do backend")
        void deveSomarAsFuturas() {
            var view = useCase.handle(new GetFutureInstallmentsQuery(USER_ID, cardId));

            assertThat(view.total()).isEqualTo(Money.of("1000.00"));
            assertThat(view.byInvoice()).extracting(b -> b.referenceMonth().toString())
                    .containsExactly("2026-12", "2027-01", "2027-02", "2027-03", "2027-04");
            assertThat(view.byInvoice()).allSatisfy(b -> {
                assertThat(b.amount()).isEqualTo(Money.of("200.00"));
                assertThat(b.items()).singleElement().satisfies(i -> assertThat(i.description()).isEqualTo("Notebook"));
            });
            assertThat(view.byInvoice().getFirst().items().getFirst().number()).isEqualTo(2);
            assertThat(view.byInvoice().getFirst().dueDate()).isEqualTo(LocalDate.of(2026, 12, 5));
            assertThat(view.byInvoice().getFirst().invoiceId()).isEqualTo(InvoiceIds.of(cardId, LocalDate.of(2026, 11, 25)));
        }

        @Test
        @DisplayName("parcela cancelada deixa de ser compromisso; sem parcelas futuras o total é 0,00 e a lista vazia")
        void naoDeveContarAsCanceladas() {
            var purchase = scenario.transactions.getFirst();
            com.moneylog.core.transaction.domain.CardTransactions.cancelRemainingInstallments(purchase, scenario.chain(), OCT_10,
                    java.time.Instant.parse("2026-10-10T15:00:00Z"));

            var view = useCase.handle(new GetFutureInstallmentsQuery(USER_ID, cardId));

            assertThat(view.total()).isEqualTo(Money.of("0.00"));
            assertThat(view.byInvoice()).isEmpty();
        }

        @Test
        @DisplayName("cartão inexistente ou de outro usuário: CARD_NOT_FOUND")
        void deveRecusarCartaoAlheio() {
            assertThatThrownBy(() -> useCase.handle(new GetFutureInstallmentsQuery(USER_ID, UUID.randomUUID())))
                    .isInstanceOf(CardNotFoundException.class);
        }
    }

    private static com.moneylog.core.card.app.gateways.InvoiceDocumentGateway documentsStub() {
        var documents = mock(com.moneylog.core.card.app.gateways.InvoiceDocumentGateway.class);
        when(documents.find(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.moneylog.core.card.app.gateways.InvoiceDocumentGateway.Stored(null, 0));
        return documents;
    }
}
