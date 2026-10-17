package com.moneylog.core.finance.app.transactions;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InstallmentStatus;
import com.moneylog.core.card.exception.CardExceptions.NoInstallmentsToCancelException;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.TransactionDetails.InstallmentView;
import com.moneylog.core.finance.app.transactions.usecase.CancelInstallmentsUseCase.CancelInstallmentsCommand;
import com.moneylog.core.finance.app.transactions.usecase.CancelInstallmentsUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.CardPurchase;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionUseCase.UpdateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCase.VoidTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCaseImpl;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.gateways.TransactionRevisionGateway;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.InvalidTransactionOperationException;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundCategoryLockedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionAlreadyVoidedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionVoidRefusedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidReasonRequiredException;

@DisplayName("Estorno, cancelamento de parcelas e edição de compra no cartão (T57)")
class CardPurchaseLifecycleUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate OCT_10 = LocalDate.of(2026, 10, 10);
    private static final LocalDate OCT_30 = LocalDate.of(2026, 10, 30);

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private final TransactionGateway transactions = mock(TransactionGateway.class);
    private final AccountGateway accounts = mock(AccountGateway.class);
    private final CardGateway cards = mock(CardGateway.class);
    private final CategoryGateway categories = mock(CategoryGateway.class);
    private final LedgerWriteGateway ledger = mock(LedgerWriteGateway.class);
    private final TransactionRevisionGateway revisions = mock(TransactionRevisionGateway.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final UUID cardId = scenario.roxinho.id();
    private final Category other = Category.create(UUID.fromString("00000000-0000-0000-0000-00000000c0c1"), USER_ID, "Tecnologia",
            CategoryKind.EXPENSE);

    private Transaction notebook;
    private Transaction mercado;
    private CancelInstallmentsUseCaseImpl cancel;
    private VoidTransactionUseCaseImpl voidUseCase;
    private UpdateTransactionUseCaseImpl update;

    @BeforeEach
    void setUp() {
        notebook = scenario.caso2CompraParcelada();
        mercado = scenario.compraAVista1083();
        when(time.now()).thenReturn(NOW);
        when(time.today()).thenReturn(OCT_10);
        serveCard(scenario.roxinho);
        when(accounts.findById(USER_ID, scenario.nubank.id())).thenReturn(Optional.of(new Persisted<>(scenario.nubank, NOW, NOW)));
        when(categories.findById(USER_ID, scenario.alimentacao.id())).thenReturn(Optional.of(scenario.alimentacao));
        when(categories.findById(USER_ID, other.id())).thenReturn(Optional.of(other));
        when(transactions.sumActiveRefundsOf(any(), any())).thenReturn(Money.of("0.00"));
        when(transactions.findAllOfCard(USER_ID, cardId)).thenAnswer(call -> scenario.transactions.stream().map(t -> copy(t)).toList());
        when(transactions.update(any())).thenAnswer(call -> new Persisted<>(call.getArgument(0), NOW, NOW));
        found(notebook);
        found(mercado);
        found(scenario.pagarFatura("0.01", LocalDate.of(2026, 10, 25), OCT_10));
        scenario.transactions.removeLast();
        TransactionDetailsAssembler assembler = new TransactionDetailsAssembler(transactions, accounts, categories, cards, time);
        cancel = new CancelInstallmentsUseCaseImpl(transactions, cards, revisions, ledger, assembler, time);
        voidUseCase = new VoidTransactionUseCaseImpl(transactions, accounts, cards, ledger, assembler, time);
        update = new UpdateTransactionUseCaseImpl(transactions, accounts, new AccountTransactionFactory(transactions, accounts, categories, cards, time),
                revisions, ledger, assembler, time);
    }

    private static Transaction copy(Transaction t) {
        return Transaction.restore(t.state(), t.entries());
    }

    private void serveCard(CreditCard card) {
        when(cards.findById(USER_ID, card.id())).thenReturn(Optional.of(new Persisted<>(card, NOW, NOW)));
    }

    private void found(Transaction t) {
        when(transactions.findById(USER_ID, t.id())).thenAnswer(call -> Optional.of(new Persisted<>(copy(t), NOW, NOW)));
    }

    private Transaction refundOf(Transaction original, String amount, LocalDate date) {
        Transaction refund = CardTransactions.refundToCard(scenario.header(date, "Estorno"), Money.of(amount), scenario.roxinho,
                scenario.alimentacao, RefundOrigin.firstRefundOf(original), scenario.chain(), date);
        scenario.transactions.add(refund);
        found(refund);
        return refund;
    }

    private void nothingWritten() {
        verify(transactions, never()).update(any());
        verify(ledger, never()).deactivateBySource(any(), any(), any(), any(), any());
        verify(ledger, never()).deactivateByInstallments(any(), any(), anyCollection(), any(), any());
        verify(ledger, never()).append(any(), any());
    }

    @Nested
    @DisplayName("cancelar as parcelas restantes")
    class Cancelar {

        @Test
        @DisplayName("CA3: com a parcela 1 em fatura fechada (30/10), as parcelas 2..6 viram CANCELED, os lançamentos delas ficam inativos e o compromisso vai a 0; a 1 permanece")
        void deveCancelarSoAsParcelasDeFaturasAbertasEFuturas() {
            when(time.today()).thenReturn(OCT_30);

            TransactionDetails details = cancel.handle(new CancelInstallmentsCommand(USER_ID, notebook.id(), "desisti do plano"));

            assertThat(details.cardInfo().installments()).extracting(InstallmentView::status).containsExactly(InstallmentStatus.PENDING,
                    InstallmentStatus.CANCELED, InstallmentStatus.CANCELED, InstallmentStatus.CANCELED, InstallmentStatus.CANCELED,
                    InstallmentStatus.CANCELED);
            assertThat(details.transaction().status()).isEqualTo(TransactionStatus.ACTIVE);
            assertThat(details.impact().commitment()).isEqualTo(Money.of("0.00"));
            assertThat(details.impact().consumption()).singleElement().satisfies(c -> assertThat(c.amount()).isEqualTo(Money.of("200.00")));
            ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.forClass(List.class);
            verify(ledger).deactivateByInstallments(eq(USER_ID), eq(notebook.id()), ids.capture(), eq("CANCEL"), eq(NOW));
            assertThat(ids.getValue()).containsExactlyElementsOf(notebook.installments().stream().skip(1).map(i -> i.id()).toList());
            assertThat(details.flags().voidable()).isFalse();
            assertThat(details.flags().voidBlockedBy()).isEqualTo(VoidBlockedBy.TRANSACTION_IN_CLOSED_INVOICE);
        }

        @Test
        @DisplayName("S-218: com reembolso ativo acima do que ficaria cobrado, recusa REFUND_EXCEEDS_ORIGINAL com a soma lida depois da trava da movimentação; nada gravado")
        void deveRecusarComReembolsoAtivoAcimaDoRestante() {
            when(time.today()).thenReturn(OCT_30);
            when(transactions.sumActiveRefundsOf(USER_ID, notebook.id())).thenReturn(Money.of("1000.00"));

            assertThatThrownBy(() -> cancel.handle(new CancelInstallmentsCommand(USER_ID, notebook.id(), "desisti")))
                    .isInstanceOf(com.moneylog.core.transaction.exception.TransactionExceptions.RefundExceedsOriginalException.class);

            InOrder order = inOrder(transactions);
            order.verify(transactions).lockForUpdate(USER_ID, notebook.id());
            order.verify(transactions).sumActiveRefundsOf(USER_ID, notebook.id());
            nothingWritten();
            verify(revisions, never()).save(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
        }

        @Test
        @DisplayName("S-218: reembolso ativo de 200 (o que continua cobrado) não impede o cancelamento")
        void deveAceitarComReembolsoAtivoDentroDoRestante() {
            when(time.today()).thenReturn(OCT_30);
            when(transactions.sumActiveRefundsOf(USER_ID, notebook.id())).thenReturn(Money.of("200.00"));

            TransactionDetails details = cancel.handle(new CancelInstallmentsCommand(USER_ID, notebook.id(), "desisti"));

            assertThat(details.cardInfo().installments()).filteredOn(i -> i.status() == InstallmentStatus.CANCELED).hasSize(5);
        }

        @Test
        @DisplayName("grava a movimentação (versão e parcelas), depois a revisão do estado anterior com o motivo, depois a desativação; trava a movimentação e depois o cartão antes de ler a cadeia")
        void deveGravarNaOrdemCerta() {
            when(time.today()).thenReturn(OCT_30);

            cancel.handle(new CancelInstallmentsCommand(USER_ID, notebook.id(), "  desisti  "));

            InOrder order = inOrder(transactions, cards, revisions, ledger);
            order.verify(transactions).lockForUpdate(USER_ID, notebook.id());
            order.verify(cards).lockForUpdate(USER_ID, cardId);
            order.verify(transactions).findAllOfCard(USER_ID, cardId);
            ArgumentCaptor<Transaction> updated = ArgumentCaptor.forClass(Transaction.class);
            order.verify(transactions).update(updated.capture());
            ArgumentCaptor<Transaction> previous = ArgumentCaptor.forClass(Transaction.class);
            order.verify(revisions).save(eq(USER_ID), previous.capture(), eq(1), eq(NOW), eq("CANCEL_INSTALLMENTS"), eq("desisti"));
            order.verify(ledger).deactivateByInstallments(any(), any(), anyCollection(), any(), any());
            assertThat(updated.getValue().installments().stream().filter(i -> i.isCanceled()).count()).isEqualTo(5);
            assertThat(previous.getValue().installments().stream().filter(i -> i.isCanceled()).count()).as("o estado anterior fica intacto").isZero();
        }

        @Test
        @DisplayName("sem parcela cancelável (todas em fatura fechada): CARD_NO_INSTALLMENTS_TO_CANCEL; estornada: TRANSACTION_ALREADY_VOIDED; não é compra: TRANSACTION_NOT_CANCELABLE; sem motivo: recusado; nada gravado")
        void deveRecusar() {
            when(time.today()).thenReturn(OCT_30);
            assertThatThrownBy(() -> cancel.handle(new CancelInstallmentsCommand(USER_ID, mercado.id(), "x")))
                    .isInstanceOf(NoInstallmentsToCancelException.class);
            Transaction voided = scenario.compraNoCartao("100.00", 2);
            scenario.estornar(voided, "erro", OCT_10);
            found(voided);
            assertThatThrownBy(() -> cancel.handle(new CancelInstallmentsCommand(USER_ID, voided.id(), "x")))
                    .isInstanceOf(TransactionAlreadyVoidedException.class);
            Transaction payment = scenario.pagarFatura("10.00", LocalDate.of(2026, 10, 25), OCT_30);
            found(payment);
            assertThatThrownBy(() -> cancel.handle(new CancelInstallmentsCommand(USER_ID, payment.id(), "x")))
                    .isInstanceOf(InvalidTransactionOperationException.class).hasFieldOrPropertyWithValue("code", "TRANSACTION_NOT_CANCELABLE");
            assertThatThrownBy(() -> cancel.handle(new CancelInstallmentsCommand(USER_ID, notebook.id(), "  ")))
                    .isInstanceOf(VoidReasonRequiredException.class);
            nothingWritten();
        }
    }

    @Nested
    @DisplayName("estornar")
    class Estornar {

        @Test
        @DisplayName("CA1: compra com todas as parcelas em faturas FUTURE/OPEN (10/10): VOIDED, lançamentos inativos, nada de fatura corrente")
        void deveEstornarCompraEmFaturaAberta() {
            TransactionDetails details = voidUseCase.handle(new VoidTransactionCommand(USER_ID, notebook.id(), "errei o valor"));

            assertThat(details.transaction().status()).isEqualTo(TransactionStatus.VOIDED);
            assertThat(details.transaction().installments()).allMatch(i -> i.isCanceled());
            assertThat(details.impact().consumption()).isEmpty();
            assertThat(details.impact().commitment()).isEqualTo(Money.of("0.00"));
            verify(ledger).deactivateBySource(USER_ID, PostingSourceType.TRANSACTION, notebook.id(), "VOID", NOW);
            InOrder order = inOrder(transactions, cards);
            order.verify(transactions).lockForUpdate(USER_ID, notebook.id());
            order.verify(cards).lockForUpdate(USER_ID, cardId);
            order.verify(transactions).findAllOfCard(USER_ID, cardId);
            order.verify(transactions).update(any());
        }

        @Test
        @DisplayName("CA2: com a parcela 1 em fatura CLOSED (30/10): TRANSACTION_IN_CLOSED_INVOICE e nada muda; a flag diz voidable false")
        void deveRecusarCompraComParcelaEmFaturaFechada() {
            when(time.today()).thenReturn(OCT_30);

            assertThatThrownBy(() -> voidUseCase.handle(new VoidTransactionCommand(USER_ID, notebook.id(), "x")))
                    .isInstanceOf(TransactionVoidRefusedException.class).hasFieldOrPropertyWithValue("code", "TRANSACTION_IN_CLOSED_INVOICE");
            nothingWritten();
        }

        @Test
        @DisplayName("compra com reembolso ativo: TRANSACTION_HAS_ACTIVE_REFUNDS (S-89); sem ele, estorna")
        void deveRecusarCompraComReembolsoAtivo() {
            Transaction refund = refundOf(mercado, "50.00", OCT_10);
            when(transactions.sumActiveRefundsOf(USER_ID, mercado.id())).thenReturn(Money.of("50.00"));

            assertThatThrownBy(() -> voidUseCase.handle(new VoidTransactionCommand(USER_ID, mercado.id(), "x")))
                    .isInstanceOf(TransactionVoidRefusedException.class).hasFieldOrPropertyWithValue("code", "TRANSACTION_HAS_ACTIVE_REFUNDS");
            nothingWritten();

            voidUseCase.handle(new VoidTransactionCommand(USER_ID, refund.id(), "desfeito"));
            when(transactions.sumActiveRefundsOf(USER_ID, mercado.id())).thenReturn(Money.of("0.00"));
            assertThat(voidUseCase.handle(new VoidTransactionCommand(USER_ID, mercado.id(), "agora sim")).transaction().status())
                    .isEqualTo(TransactionStatus.VOIDED);
        }

        @Test
        @DisplayName("CA5: crédito em fatura OPEN é estornado (a fatura volta a somar); em fatura CLOSED/PAID, TRANSACTION_IN_CLOSED_INVOICE")
        void deveEstornarCreditoSoEmFaturaAberta() {
            Transaction refund = refundOf(mercado, "50.00", OCT_10);

            assertThat(voidUseCase.handle(new VoidTransactionCommand(USER_ID, refund.id(), "x")).transaction().status())
                    .isEqualTo(TransactionStatus.VOIDED);

            Transaction another = refundOf(mercado, "20.00", OCT_10);
            when(time.today()).thenReturn(OCT_30);
            assertThatThrownBy(() -> voidUseCase.handle(new VoidTransactionCommand(USER_ID, another.id(), "x")))
                    .isInstanceOf(TransactionVoidRefusedException.class).hasFieldOrPropertyWithValue("code", "TRANSACTION_IN_CLOSED_INVOICE");
        }

        @Test
        @DisplayName("CA7: cartão arquivado: estornar continua permitido se as guardas deixam")
        void deveEstornarComCartaoArquivado() {
            serveCard(CreditCardTestFactory.umCartaoArquivado());

            assertThat(voidUseCase.handle(new VoidTransactionCommand(USER_ID, notebook.id(), "x")).transaction().status())
                    .isEqualTo(TransactionStatus.VOIDED);
        }
    }

    @Nested
    @DisplayName("editar (descrição, categoria e observação)")
    class Editar {

        private CardPurchase body(String amount, LocalDate date, int installments, UUID card, UUID category, String description) {
            return new CardPurchase(new Common(USER_ID, Money.of(amount), date, description, "obs"), card, category, installments);
        }

        @Test
        @DisplayName("CA4: muda descrição, categoria e observação: a revisão guarda o anterior, os 12 lançamentos são regravados na categoria nova e as parcelas e faturas não mudam")
        void deveEditarOsCamposLivres() {
            TransactionDetails details = update.handle(new UpdateTransactionCommand(notebook.id(), 0,
                    body("1200.00", OCT_10, 6, cardId, other.id(), "Notebook Dell")));

            ArgumentCaptor<Transaction> edited = ArgumentCaptor.forClass(Transaction.class);
            InOrder order = inOrder(transactions, revisions, ledger);
            order.verify(transactions).update(edited.capture());
            ArgumentCaptor<Transaction> previous = ArgumentCaptor.forClass(Transaction.class);
            order.verify(revisions).save(eq(USER_ID), previous.capture(), eq(1), eq(NOW));
            order.verify(ledger).deactivateBySource(USER_ID, PostingSourceType.TRANSACTION, notebook.id(), "EDIT", NOW);
            ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
            order.verify(ledger).append(eq(USER_ID), entries.capture());
            assertThat(previous.getValue().description()).isEqualTo("Notebook");
            assertThat(previous.getValue().categoryId()).isEqualTo(scenario.alimentacao.id());
            assertThat(edited.getValue().description()).isEqualTo("Notebook Dell");
            assertThat(edited.getValue().notes()).isEqualTo("obs");
            assertThat(edited.getValue().categoryId()).isEqualTo(other.id());
            assertThat(edited.getValue().installments()).isEqualTo(notebook.installments());
            assertThat(entries.getValue()).hasSize(12).allMatch(LedgerEntry::active);
            assertThat(entries.getValue().stream().filter(e -> e.posting().bookType() == BookType.EXPENSE))
                    .hasSize(6).allMatch(e -> e.posting().book().id().equals(other.id()));
            assertThat(entries.getValue().stream().mapToLong(e -> e.posting().amount().cents()).sum()).isZero();
            assertThat(details.category()).isEqualTo(other);
            assertThat(details.flags().editable()).isTrue();
        }

        @Test
        @DisplayName("com parcelas canceladas, só os lançamentos das ativas são regravados (os das canceladas ficam inativos no histórico)")
        void deveManterInativosOsLancamentosDasCanceladas() {
            Transaction purchase = scenario.compraNoCartao("300.00", 3);
            CardTransactions.cancelRemainingInstallments(purchase, scenario.chain(), OCT_10, NOW);
            found(purchase);
            assertThat(purchase.installments().stream().filter(i -> i.isCanceled())).hasSize(3);
            Transaction partial = scenario.compraNoCartao("300.00", 3);
            when(time.today()).thenReturn(LocalDate.of(2026, 11, 26));
            CardTransactions.cancelRemainingInstallments(partial, scenario.chain(), LocalDate.of(2026, 11, 26), NOW);
            found(partial);
            assertThat(partial.installments().stream().filter(i -> i.isCanceled()).count()).isGreaterThanOrEqualTo(1);

            update.handle(new UpdateTransactionCommand(partial.id(), 0, body("300.00", OCT_10, 3, cardId, other.id(), "Curso")));

            ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
            verify(ledger).append(eq(USER_ID), entries.capture());
            long active = partial.installments().stream().filter(i -> !i.isCanceled()).count();
            assertThat(entries.getValue()).hasSize((int) active * 2).allMatch(LedgerEntry::active);
        }

        @Test
        @DisplayName("CA4: valor, data, parcelas ou cartão diferentes: TRANSACTION_NOT_EDITABLE com a explicação, nada é gravado")
        void deveRecusarMudarOQueNaoMuda() {
            UUID otherCard = UUID.fromString("00000000-0000-0000-0000-00000000d002");
            for (CardPurchase changed : List.of(body("1300.00", OCT_10, 6, cardId, other.id(), "x"), body("1200.00", OCT_10.plusDays(1), 6, cardId, other.id(), "x"),
                    body("1200.00", OCT_10, 5, cardId, other.id(), "x"), body("1200.00", OCT_10, 6, otherCard, other.id(), "x"))) {
                assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(notebook.id(), 0, changed)))
                        .isInstanceOf(TransactionNotEditableException.class).hasMessageContaining("não mudam");
            }
            nothingWritten();
            verify(revisions, never()).save(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        }

        @Test
        @DisplayName("compra com reembolso ativo não troca de categoria: REFUND_CATEGORY_LOCKED; descrição e observação, sim")
        void deveTravarACategoriaComReembolsoAtivo() {
            when(transactions.sumActiveRefundsOf(USER_ID, mercado.id())).thenReturn(Money.of("50.00"));

            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(mercado.id(), 0,
                    body("1083.00", LocalDate.of(2026, 10, 12), 1, cardId, other.id(), "x")))).isInstanceOf(RefundCategoryLockedException.class);
            nothingWritten();
            update.handle(new UpdateTransactionCommand(mercado.id(), 0,
                    body("1083.00", LocalDate.of(2026, 10, 12), 1, cardId, scenario.alimentacao.id(), "Supermercado")));
            verify(ledger).append(eq(USER_ID), any());
        }

        @Test
        @DisplayName("crédito no cartão e pagamento de fatura não são editáveis; compra estornada também não; cartão arquivado não impede editar")
        void deveRecusarOutrasMovimentacoesDeCartao() {
            Transaction refund = refundOf(mercado, "50.00", OCT_10);
            Transaction payment = scenario.pagarFatura("10.00", LocalDate.of(2026, 10, 25), OCT_30);
            found(payment);
            CreateTransactionCommand refundBody = new CreateTransactionCommand.Refund(new Common(USER_ID, Money.of("50.00"), OCT_10, "x", null), null,
                    scenario.alimentacao.id(), null, cardId);

            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(refund.id(), 0, refundBody)))
                    .isInstanceOf(TransactionNotEditableException.class);
            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(payment.id(), 0, new CreateTransactionCommand.Unavailable(USER_ID,
                    TransactionType.INVOICE_PAYMENT)))).isInstanceOf(TransactionNotEditableException.class);
            nothingWritten();

            serveCard(CreditCardTestFactory.umCartaoArquivado());
            update.handle(new UpdateTransactionCommand(notebook.id(), 0, body("1200.00", OCT_10, 6, cardId, other.id(), "Arquivado")));
            verify(ledger).append(eq(USER_ID), any());
        }
    }

    @Test
    @DisplayName("flags: compra aberta é editable e voidable; em fatura fechada, voidBlockedBy TRANSACTION_IN_CLOSED_INVOICE; com reembolso, HAS_ACTIVE_REFUNDS; crédito não é editable")
    void deveCalcularAsFlagsDoCartao() {
        var open = TransactionFlags.ofCard(notebook, List.of(), false, scenario.chain(), OCT_10);
        assertThat(open.editable()).isTrue();
        assertThat(open.voidable()).isTrue();
        var closed = TransactionFlags.ofCard(notebook, List.of(), false, scenario.chain(), OCT_30);
        assertThat(closed.editable()).isTrue();
        assertThat(closed.voidable()).isFalse();
        assertThat(closed.voidBlockedBy()).isEqualTo(VoidBlockedBy.TRANSACTION_IN_CLOSED_INVOICE);
        assertThat(TransactionFlags.ofCard(mercado, List.of(), true, scenario.chain(), OCT_10).voidBlockedBy())
                .isEqualTo(VoidBlockedBy.HAS_ACTIVE_REFUNDS);
        Transaction refund = refundOf(mercado, "50.00", OCT_10);
        assertThat(TransactionFlags.ofCard(refund, List.of(), false, scenario.chain(), OCT_10)).satisfies(f -> {
            assertThat(f.editable()).isFalse();
            assertThat(f.voidable()).isTrue();
        });
        assertThat(TransactionFlags.ofCard(refund, List.of(), false, scenario.chain(), OCT_30).voidBlockedBy())
                .isEqualTo(VoidBlockedBy.TRANSACTION_IN_CLOSED_INVOICE);
    }
}
