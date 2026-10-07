package com.moneylog.core.finance.app.transactions;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
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
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCaseImpl;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Idempotência na criação de movimentações (T26b, S-72, S-87)")
class IdempotentCreationTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final UUID NEW_ID = UUID.fromString("00000000-0000-0000-0000-0000000e0001");
    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-0000000f0001");
    private static final String SCOPE = "POST /v1/transactions";

    @Mock
    private TransactionGateway transactions;
    @Mock
    private AccountGateway accounts;
    @Mock
    private CategoryGateway categories;
    @Mock
    private LedgerWriteGateway ledger;
    @Mock
    private IdGenerator ids;
    @Mock
    private IdempotencyGateway idempotency;
    @Mock
    private TimeProvider time;

    private final Account nubank = AccountTestFactory.umaContaCorrente();
    private final Category food = CategoryTestFactory.umaCategoriaDeDespesa();
    private CreateTransactionUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        when(accounts.findById(USER_ID, nubank.id())).thenReturn(Optional.of(new Persisted<>(nubank, NOW, NOW)));
        when(categories.findById(USER_ID, food.id())).thenReturn(Optional.of(food));
        when(ids.next()).thenReturn(NEW_ID);
        when(time.now()).thenReturn(NOW);
        when(transactions.insert(any())).thenAnswer(call -> new Persisted<>(call.getArgument(0), NOW, NOW));
        when(transactions.sumActiveRefundsOf(any(), any())).thenReturn(Money.of("0.00"));
        useCase = new CreateTransactionUseCaseImpl(transactions, new AccountTransactionFactory(transactions, accounts, categories), ledger,
                ids, new TransactionDetailsAssembler(transactions, accounts, categories), idempotency, time);
    }

    private CreateTransactionCommand expense(String amount) {
        return new CreateTransactionCommand.Expense(new Common(USER_ID, Money.of(amount), LocalDate.of(2026, 10, 10), "Restaurante", null),
                nubank.id(), food.id(), PaymentMethod.PIX);
    }

    @Test
    @DisplayName("chave nova: reivindica com o hash do comando e o id novo ANTES de gravar, e grava a movimentação")
    void deveReivindicarAChaveAntesDeGravar() {
        CreateTransactionCommand command = expense("100.00");
        when(idempotency.claim(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(true);

        useCase.handle(command, KEY);

        InOrder order = inOrder(idempotency, transactions, ledger);
        ArgumentCaptor<Instant> expiredBefore = ArgumentCaptor.forClass(Instant.class);
        order.verify(idempotency).claim(eq(USER_ID), eq(SCOPE), eq(KEY), eq(CanonicalCommandHash.of(command)), eq(NEW_ID), eq(NOW),
                expiredBefore.capture());
        order.verify(transactions).insert(any(Transaction.class));
        order.verify(ledger).append(eq(USER_ID), any());
        assertThat(expiredBefore.getValue()).isEqualTo(NOW.minusSeconds(24 * 3600));
    }

    @Test
    @DisplayName("mesma chave e mesmo comando: devolve o estado ATUAL do recurso criado e não grava de novo")
    void deveFazerReplayDoEstadoAtual() {
        CreateTransactionCommand command = expense("100.00");
        UUID created = UUID.fromString("00000000-0000-0000-0000-0000000e00aa");
        Transaction current = new AccountTransactionFactory(transactions, accounts, categories)
                .build(command, created, com.moneylog.core.transaction.domain.TransactionOrigin.manual(), null);
        when(idempotency.claim(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(false);
        when(idempotency.find(USER_ID, SCOPE, KEY)).thenReturn(Optional.of(new Claim(CanonicalCommandHash.of(command), created, NOW)));
        when(transactions.findById(USER_ID, created)).thenReturn(Optional.of(new Persisted<>(current, NOW, NOW)));

        var details = useCase.handle(command, KEY);

        assertThat(details.transaction().id()).isEqualTo(created);
        verify(transactions, never()).insert(any());
        verify(ledger, never()).append(any(), any());
    }

    @Test
    @DisplayName("mesma chave com outro comando: IDEMPOTENCY_KEY_REUSED e nada é gravado")
    void deveRecusarComandoDiferente() {
        when(idempotency.claim(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(false);
        when(idempotency.find(USER_ID, SCOPE, KEY)).thenReturn(Optional.of(new Claim(CanonicalCommandHash.of(expense("100.00")), NEW_ID, NOW)));

        assertThatThrownBy(() -> useCase.handle(expense("100.01"), KEY)).isInstanceOf(IdempotencyKeyReusedException.class)
                .hasFieldOrPropertyWithValue("code", "IDEMPOTENCY_KEY_REUSED");
        verify(transactions, never()).insert(any());
    }

    @Test
    @DisplayName("recurso da chave sumiu: TRANSACTION_NOT_FOUND (não recria em silêncio)")
    void deveFalharSeORecursoNaoExiste() {
        CreateTransactionCommand command = expense("100.00");
        when(idempotency.claim(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(false);
        when(idempotency.find(USER_ID, SCOPE, KEY)).thenReturn(Optional.of(new Claim(CanonicalCommandHash.of(command), NEW_ID, NOW)));
        when(transactions.findById(USER_ID, NEW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle(command, KEY)).isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    @DisplayName("a chave some entre o claim recusado e o find (rollback da concorrente): tenta de novo e reivindica")
    void deveTentarDeNovoQuandoAChaveSome() {
        CreateTransactionCommand command = expense("100.00");
        when(idempotency.claim(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(false, true);
        when(idempotency.find(USER_ID, SCOPE, KEY)).thenReturn(Optional.empty());

        useCase.handle(command, KEY);

        verify(transactions).insert(any(Transaction.class));
    }

    @Test
    @DisplayName("sem chave de idempotência não toca o gateway da chave")
    void devePermitirSemChave() {
        useCase.handle(expense("100.00"));

        verify(idempotency, never()).claim(any(), any(), any(), any(), any(), any(), any());
        verify(transactions).insert(any(Transaction.class));
    }

    @Test
    @DisplayName("falha do domínio depois do claim propaga (o rollback da transação tira a chave); nada é gravado")
    void devePropagarFalhaDeDominio() {
        when(idempotency.claim(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(true);
        CreateTransactionCommand invalid = new CreateTransactionCommand.Transfer(
                new Common(USER_ID, Money.of("1.00"), LocalDate.of(2026, 10, 10), "x", null), nubank.id(), nubank.id());

        assertThatThrownBy(() -> useCase.handle(invalid, KEY)).hasFieldOrPropertyWithValue("code", "TRANSFER_SAME_ACCOUNT");
        verify(transactions, never()).insert(any());
        verify(ledger, never()).append(any(), any());
    }
}
