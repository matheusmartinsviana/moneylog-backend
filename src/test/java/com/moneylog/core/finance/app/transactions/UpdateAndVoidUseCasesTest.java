package com.moneylog.core.finance.app.transactions;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionUseCase.UpdateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCase.VoidTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCaseImpl;
import com.moneylog.core.finance.util.ListScenario;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.gateways.TransactionRevisionGateway;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Casos de uso de edição e estorno (T27)")
class UpdateAndVoidUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    @Mock
    private TransactionGateway transactions;
    @Mock
    private AccountGateway accounts;
    @Mock
    private CategoryGateway categories;
    @Mock
    private LedgerWriteGateway ledger;
    @Mock
    private TransactionRevisionGateway revisions;
    @Mock
    private TimeProvider time;

    private final ListScenario s = new ListScenario();
    private UpdateTransactionUseCaseImpl update;
    private VoidTransactionUseCaseImpl voidUseCase;

    @BeforeEach
    void setUp() {
        for (Account account : List.of(s.nubank, s.xp, s.xpInvest)) {
            serve(account);
        }
        when(categories.findById(USER_ID, s.alimentacao.id())).thenReturn(Optional.of(s.alimentacao));
        when(categories.findById(USER_ID, s.transporte.id())).thenReturn(Optional.of(s.transporte));
        when(categories.findById(USER_ID, s.salario.id())).thenReturn(Optional.of(s.salario));
        when(time.now()).thenReturn(NOW);
        when(time.today()).thenReturn(TODAY);
        when(transactions.sumActiveRefundsOf(any(), any())).thenReturn(Money.of("0.00"));
        when(transactions.update(any())).thenAnswer(call -> new Persisted<>(call.getArgument(0), NOW, NOW));
        TransactionDetailsAssembler assembler = new TransactionDetailsAssembler(transactions, accounts, categories);
        update = new UpdateTransactionUseCaseImpl(transactions, accounts, new AccountTransactionFactory(transactions, accounts, categories),
                revisions, ledger, assembler, time);
        voidUseCase = new VoidTransactionUseCaseImpl(transactions, accounts, ledger, assembler, time);
        found(s.pix);
        found(s.debito);
        found(s.transferencia);
        found(s.reembolso);
        found(s.estornada);
    }

    private void serve(Account account) {
        when(accounts.findById(USER_ID, account.id())).thenReturn(Optional.of(new Persisted<>(account, NOW, NOW)));
    }

    private void found(Transaction transaction) {
        when(transactions.findById(USER_ID, transaction.id())).thenReturn(Optional.of(new Persisted<>(transaction, NOW, NOW)));
    }

    private Account archived(Account account) {
        return Account.restore(account.id(), account.userId(), account.name(), AccountType.CHECKING, null, account.openingBalance(),
                account.openingDate(), AccountStatus.ARCHIVED, 1);
    }

    private static Common common(String amount, String description) {
        return new Common(USER_ID, Money.of(amount), TODAY, description, null);
    }

    private CreateTransactionCommand expense(String amount, UUID categoryId) {
        return new CreateTransactionCommand.Expense(common(amount, "Restaurante"), s.nubank.id(), categoryId, PaymentMethod.PIX);
    }

    private void nothingWritten() {
        verifyNoInteractions(revisions);
        verify(transactions, never()).update(any());
        verify(ledger, never()).deactivateBySource(any(), any(), any(), any(), any());
        verify(ledger, never()).append(any(), any());
    }

    @Nested
    @DisplayName("PUT: edição")
    class Edicao {

        @Test
        @DisplayName("linha (checa a versão) → revisão do estado anterior → desativa os lançamentos (EDIT) → novos lançamentos balanceados: a revisão vem antes da troca dos lançamentos")
        void deveGravarNaOrdemCerta() {
            TransactionDetails details = update.handle(new UpdateTransactionCommand(s.pix.id(), 0, expense("120.00", s.transporte.id())));

            InOrder order = inOrder(revisions, transactions, ledger);
            ArgumentCaptor<Transaction> edited = ArgumentCaptor.forClass(Transaction.class);
            order.verify(transactions).update(edited.capture());
            ArgumentCaptor<Transaction> previous = ArgumentCaptor.forClass(Transaction.class);
            order.verify(revisions).save(eq(USER_ID), previous.capture(), eq(1), eq(NOW));
            order.verify(ledger).deactivateBySource(USER_ID, PostingSourceType.TRANSACTION, s.pix.id(), "EDIT", NOW);
            ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
            order.verify(ledger).append(eq(USER_ID), entries.capture());

            assertThat(previous.getValue().amount()).isEqualTo(Money.of("100.00"));
            assertThat(previous.getValue().categoryId()).isEqualTo(s.alimentacao.id());
            assertThat(edited.getValue().amount()).isEqualTo(Money.of("120.00"));
            assertThat(edited.getValue().id()).isEqualTo(s.pix.id());
            assertThat(entries.getValue()).isEqualTo(edited.getValue().entries());
            assertThat(entries.getValue().stream().map(e -> e.posting().amount().cents()).reduce(0L, Long::sum)).isZero();
            assertThat(details.impact().consumption()).singleElement().satisfies(c -> assertThat(c.amount()).isEqualTo(Money.of("120.00")));
            assertThat(details.category()).isEqualTo(s.transporte);
        }

        @Test
        @DisplayName("versão desatualizada: VERSION_CONFLICT e nada é gravado")
        void deveRecusarVersaoAntiga() {
            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(s.pix.id(), 5, expense("120.00", s.transporte.id()))))
                    .isInstanceOf(VersionConflictException.class).hasFieldOrPropertyWithValue("code", "VERSION_CONFLICT");
            nothingWritten();
        }

        @Test
        @DisplayName("tipo diferente, movimentação estornada: TRANSACTION_NOT_EDITABLE; inexistente: TRANSACTION_NOT_FOUND; nada é gravado")
        void deveRecusarQuandoNaoEditavel() {
            CreateTransactionCommand income = new CreateTransactionCommand.Income(common("100.00", "x"), s.nubank.id(), s.salario.id(), PaymentMethod.PIX);

            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(s.pix.id(), 0, income))).isInstanceOf(TransactionNotEditableException.class);
            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(s.pix.id(), 0,
                    new CreateTransactionCommand.Unavailable(USER_ID, TransactionType.CARD_PURCHASE)))).isInstanceOf(TransactionNotEditableException.class);
            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(s.estornada.id(), 0, expense("10.00", s.alimentacao.id()))))
                    .isInstanceOf(TransactionNotEditableException.class);
            UUID missing = UUID.fromString("00000000-0000-0000-0000-0000000b00ff");
            when(transactions.findById(USER_ID, missing)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(missing, 0, expense("10.00", s.alimentacao.id()))))
                    .isInstanceOf(TransactionNotFoundException.class);
            nothingWritten();
        }

        @Test
        @DisplayName("S-73: movimentação que toca conta arquivada não é editável (ACCOUNT_ARCHIVED); a conta nova arquivada também é recusada")
        void deveRecusarContaArquivada() {
            serve(archived(s.xp));
            CreateTransactionCommand transfer = new CreateTransactionCommand.Transfer(common("10.00", "x"), s.nubank.id(), s.xp.id());

            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(s.transferencia.id(), 0, transfer)))
                    .hasFieldOrPropertyWithValue("code", "ACCOUNT_ARCHIVED");
            // movimentação cuja conta antiga está ok, mas o destino novo está arquivado
            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(s.debito.id(), 0, new CreateTransactionCommand.Expense(
                    common("10.00", "x"), s.xp.id(), s.transporte.id(), PaymentMethod.PIX)))).hasFieldOrPropertyWithValue("code", "ACCOUNT_ARCHIVED");
            nothingWritten();
        }

        @Test
        @DisplayName("S-81 (a): despesa com reembolsos ativos: categoria trava, valor abaixo da soma é recusado, igual ou maior passa; nada muda nas recusas")
        void deveAplicarOInvarianteDaDespesa() {
            when(transactions.sumActiveRefundsOf(USER_ID, s.pix.id())).thenReturn(Money.of("50.00"));

            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(s.pix.id(), 0, expense("100.00", s.transporte.id()))))
                    .hasFieldOrPropertyWithValue("code", "REFUND_CATEGORY_LOCKED");
            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(s.pix.id(), 0, expense("40.00", s.alimentacao.id()))))
                    .hasFieldOrPropertyWithValue("code", "REFUND_EXCEEDS_ORIGINAL");
            nothingWritten();

            assertThat(update.handle(new UpdateTransactionCommand(s.pix.id(), 0, expense("50.00", s.alimentacao.id()))).transaction().amount())
                    .isEqualTo(Money.of("50.00"));
            assertThat(update.handle(new UpdateTransactionCommand(s.pix.id(), 0, expense("80.00", s.alimentacao.id()))).transaction().amount())
                    .isEqualTo(Money.of("80.00"));
        }

        @Test
        @DisplayName("S-81 (b): editar um reembolso não conta o próprio valor: teto = original − os outros reembolsos")
        void deveRevalidarOReembolsoSemContarASiMesmo() {
            // R1 (reembolso do cenário, 50,00) e R2 de 20,00 sobre a despesa de 100,00; R1 passa a 30 para o exemplo do plano
            Transaction r1 = AccountTransactions.refundToAccount(TransactionHeader.of(UUID.fromString("00000000-0000-0000-0000-0000000b0a01"), USER_ID, TODAY, "R1"),
                    Money.of("30.00"), s.nubank, s.alimentacao, RefundOrigin.firstRefundOf(s.pix));
            found(r1);
            when(transactions.sumActiveRefundsOf(USER_ID, s.pix.id())).thenReturn(Money.of("50.00"));

            CreateTransactionCommand.Refund to80 = new CreateTransactionCommand.Refund(common("80.00", "R1"), s.nubank.id(), s.alimentacao.id(), s.pix.id(), null);
            CreateTransactionCommand.Refund to8001 = new CreateTransactionCommand.Refund(common("80.01", "R1"), s.nubank.id(), s.alimentacao.id(), s.pix.id(), null);
            CreateTransactionCommand.Refund otherCategory = new CreateTransactionCommand.Refund(common("10.00", "R1"), s.nubank.id(), s.transporte.id(), s.pix.id(), null);

            assertThat(update.handle(new UpdateTransactionCommand(r1.id(), 0, to80)).transaction().amount()).isEqualTo(Money.of("80.00"));
            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(r1.id(), 0, to8001))).hasFieldOrPropertyWithValue("code", "REFUND_EXCEEDS_ORIGINAL");
            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(r1.id(), 0, otherCategory))).hasFieldOrPropertyWithValue("code", "REFUND_INVALID_ORIGINAL");
        }

        @Test
        @DisplayName("regra do domínio na substituta (mesma conta na transferência) recusa sem gravar")
        void deveRecusarRegraDoDominio() {
            CreateTransactionCommand same = new CreateTransactionCommand.Transfer(common("10.00", "x"), s.nubank.id(), s.nubank.id());

            assertThatThrownBy(() -> update.handle(new UpdateTransactionCommand(s.transferencia.id(), 0, same))).hasFieldOrPropertyWithValue("code", "TRANSFER_SAME_ACCOUNT");
            nothingWritten();
        }
    }

    @Nested
    @DisplayName("POST /void: estorno")
    class Estorno {

        @Test
        @DisplayName("estorna pelo ponto único: linha VOIDED com motivo e instante, depois os lançamentos inativos (VOID); impacto zerado")
        void deveEstornar() {
            TransactionDetails details = voidUseCase.handle(new VoidTransactionCommand(USER_ID, s.debito.id(), "lançado em duplicidade"));

            InOrder order = inOrder(transactions, ledger);
            ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
            order.verify(transactions).update(saved.capture());
            order.verify(ledger).deactivateBySource(USER_ID, PostingSourceType.TRANSACTION, s.debito.id(), "VOID", NOW);
            assertThat(saved.getValue().status()).isEqualTo(TransactionStatus.VOIDED);
            assertThat(saved.getValue().voidReason()).isEqualTo("lançado em duplicidade");
            assertThat(saved.getValue().voidedAt()).isEqualTo(NOW);
            assertThat(details.impact().cash()).isEqualTo(Money.of("0.00"));
            assertThat(details.flags().voidable()).isFalse();
            assertThat(details.flags().editable()).isFalse();
        }

        @Test
        @DisplayName("já estornada: TRANSACTION_ALREADY_VOIDED; sem motivo: TRANSACTION_VOID_REASON_REQUIRED; inexistente: 404; nada é gravado")
        void deveRecusarEstornoInvalido() {
            assertThatThrownBy(() -> voidUseCase.handle(new VoidTransactionCommand(USER_ID, s.estornada.id(), "de novo")))
                    .hasFieldOrPropertyWithValue("code", "TRANSACTION_ALREADY_VOIDED");
            assertThatThrownBy(() -> voidUseCase.handle(new VoidTransactionCommand(USER_ID, s.transferencia.id(), " ")))
                    .hasFieldOrPropertyWithValue("code", "TRANSACTION_VOID_REASON_REQUIRED");
            UUID missing = UUID.fromString("00000000-0000-0000-0000-0000000b00ff");
            when(transactions.findById(USER_ID, missing)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> voidUseCase.handle(new VoidTransactionCommand(USER_ID, missing, "x"))).isInstanceOf(TransactionNotFoundException.class);
            verify(transactions, never()).update(any());
            verify(ledger, never()).deactivateBySource(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("S-73: conta arquivada recusa o estorno com ACCOUNT_ARCHIVED")
        void deveRecusarComContaArquivada() {
            serve(archived(s.xp));

            assertThatThrownBy(() -> voidUseCase.handle(new VoidTransactionCommand(USER_ID, s.transferencia.id(), "engano")))
                    .hasFieldOrPropertyWithValue("code", "ACCOUNT_ARCHIVED");
            verify(transactions, never()).update(any());
            verify(ledger, never()).deactivateBySource(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("S-89: despesa com reembolso ativo recusa com TRANSACTION_HAS_ACTIVE_REFUNDS; depois de estornar o reembolso a despesa estorna")
        void deveRecusarDespesaComReembolso() {
            when(transactions.sumActiveRefundsOf(USER_ID, s.pix.id())).thenReturn(Money.of("50.00"));

            assertThatThrownBy(() -> voidUseCase.handle(new VoidTransactionCommand(USER_ID, s.pix.id(), "engano")))
                    .hasFieldOrPropertyWithValue("code", "TRANSACTION_HAS_ACTIVE_REFUNDS");
            verify(transactions, never()).update(any());
            verify(ledger, never()).deactivateBySource(any(), any(), any(), any(), any());

            when(transactions.sumActiveRefundsOf(USER_ID, s.pix.id())).thenReturn(Money.of("0.00"));
            assertThat(voidUseCase.handle(new VoidTransactionCommand(USER_ID, s.pix.id(), "engano")).transaction().status())
                    .isEqualTo(TransactionStatus.VOIDED);
        }
    }
}
