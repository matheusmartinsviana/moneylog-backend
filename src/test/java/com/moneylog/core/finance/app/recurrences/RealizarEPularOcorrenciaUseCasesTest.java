package com.moneylog.core.finance.app.recurrences;

import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.exception.AccountExceptions.AccountArchivedException;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.finance.app.recurrences.usecase.RealizeCardRecurrenceUseCase;
import com.moneylog.core.finance.app.recurrences.usecase.RealizeCardRecurrenceUseCase.RealizeCardRecurrenceCommand;
import com.moneylog.core.finance.app.recurrences.usecase.RealizeOccurrenceUseCase.RealizeOccurrenceCommand;
import com.moneylog.core.finance.app.recurrences.usecase.RealizeOccurrenceUseCaseImpl;
import com.moneylog.core.finance.app.recurrences.usecase.SkipOccurrenceUseCase.SkipOccurrenceCommand;
import com.moneylog.core.finance.app.recurrences.usecase.SkipOccurrenceUseCaseImpl;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.recurrence.app.OccurrenceView;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.OccurrenceStatus;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.NotAnOccurrenceException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceAlreadyRealizedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceSkippedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;
import com.moneylog.core.recurrence.util.InMemoryRecurrenceGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;

@DisplayName("Realizar e pular ocorrências: casos de uso (T80)")
class RealizarEPularOcorrenciaUseCasesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final UUID RECURRENCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000ec0a1");
    private static final UUID CARD_RECURRENCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000ec0a2");

    private final List<Transaction> stored = new ArrayList<>();
    private final InMemoryRecurrenceGateway recurrences = new InMemoryRecurrenceGateway(() -> stored);
    private final AccountGateway accounts = mock(AccountGateway.class);
    private final CategoryGateway categories = mock(CategoryGateway.class);
    private final TransactionGateway transactions = mock(TransactionGateway.class);
    private final LedgerWriteGateway ledger = mock(LedgerWriteGateway.class);
    private final IdempotencyGateway idempotency = mock(IdempotencyGateway.class);
    private final RealizeCardRecurrenceUseCase cardRealization = mock(RealizeCardRecurrenceUseCase.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final Account nubank = AccountTestFactory.umaContaCorrente();
    private final Category bills = CategoryTestFactory.umaCategoriaDeDespesa();
    private int sequence = 1;
    private final IdGenerator ids = () -> UUID.fromString(String.format("00000000-0000-0000-0000-0000000d%04d", sequence++));

    private RealizeOccurrenceUseCaseImpl realize;
    private SkipOccurrenceUseCaseImpl skip;

    @BeforeEach
    void setUp() {
        when(time.today()).thenReturn(TODAY);
        when(time.now()).thenReturn(NOW);
        when(accounts.findById(USER_ID, nubank.id())).thenReturn(Optional.of(new Persisted<>(nubank, NOW, NOW)));
        when(categories.findById(USER_ID, bills.id())).thenReturn(Optional.of(bills));
        when(transactions.sumActiveRefundsOf(any(), any())).thenReturn(Money.of("0.00"));
        when(transactions.insert(any())).thenAnswer(call -> {
            Transaction tx = call.getArgument(0);
            stored.add(tx);
            return new Persisted<>(tx, NOW, NOW);
        });
        when(transactions.findById(any(), any())).thenAnswer(call -> stored.stream().filter(t -> t.id().equals(call.getArgument(1)))
                .findFirst().map(t -> new Persisted<>(t, NOW, NOW)));
        recurrences.save(RecurringTransaction.ofAccount(RECURRENCE_ID, USER_ID, TransactionType.EXPENSE, Money.of("119.00"), "Internet", bills.id(),
                nubank.id(), PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, LocalDate.of(2026, 10, 10))));
        recurrences.save(RecurringTransaction.ofCard(CARD_RECURRENCE_ID, USER_ID, Money.of("49.90"), "Streaming", bills.id(),
                CreditCardTestFactory.ROXINHO_ID, Schedule.of(Frequency.MONTHLY, LocalDate.of(2026, 9, 15))));
        TransactionDetailsAssembler assembler = new TransactionDetailsAssembler(transactions, accounts, categories);
        realize = new RealizeOccurrenceUseCaseImpl(recurrences, accounts, categories, transactions, ledger, assembler, idempotency, cardRealization, ids,
                time);
        skip = new SkipOccurrenceUseCaseImpl(recurrences);
    }

    private RealizeOccurrenceCommand command(String date, String amount, UUID key) {
        return new RealizeOccurrenceCommand(USER_ID, RECURRENCE_ID, LocalDate.parse(date), amount == null ? null : Money.of(amount), key);
    }

    @Test
    @DisplayName("realizar sem amount cria a EXPENSE de 119,00 em 10/10 com a origem RECURRENCE; lock da recorrência e da conta antes da gravação; lançamentos gravados")
    void deveRealizarNaConta() {
        TransactionDetails details = realize.handle(command("2026-10-10", null, null));
        Transaction tx = details.transaction();
        assertThat(tx.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(tx.amount()).isEqualTo(Money.of("119.00"));
        assertThat(tx.date()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(tx.origin().source()).isEqualTo(TransactionSource.RECURRENCE);
        assertThat(tx.origin().recurrenceId()).isEqualTo(RECURRENCE_ID);
        assertThat(tx.origin().occurrenceDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(details.impact().cash()).isEqualTo(Money.of("-119.00"));
        assertThat(recurrences.locks()).isEqualTo(1);
        InOrder order = inOrder(accounts, transactions, ledger);
        order.verify(accounts).lockForUpdate(USER_ID, nubank.id());
        order.verify(transactions).insert(any(Transaction.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
        order.verify(ledger).append(eq(USER_ID), entries.capture());
        assertThat(entries.getValue()).hasSize(2);
        TransactionDetails actual = realize.handle(command("2026-11-10", "124.90", null));
        assertThat(actual.transaction().amount()).isEqualTo(Money.of("124.90"));
        assertThatThrownBy(() -> realize.handle(command("2026-10-10", null, null))).isInstanceOf(OccurrenceAlreadyRealizedException.class);
        assertThat(stored).hasSize(2);
    }

    @Test
    @DisplayName("realizar recusa data que não é ocorrência, ocorrência pulada, recorrência de outro usuário e conta arquivada; nada é gravado")
    void deveRecusar() {
        assertThatThrownBy(() -> realize.handle(command("2026-10-11", null, null))).isInstanceOf(NotAnOccurrenceException.class);
        assertThatThrownBy(() -> realize.handle(new RealizeOccurrenceCommand(OTHER_USER_ID, RECURRENCE_ID, LocalDate.of(2026, 10, 10), null, null)))
                .isInstanceOf(RecurrenceNotFoundException.class);
        skip.handle(new SkipOccurrenceCommand(USER_ID, RECURRENCE_ID, LocalDate.of(2026, 11, 10)));
        assertThatThrownBy(() -> realize.handle(command("2026-11-10", null, null))).isInstanceOf(OccurrenceSkippedException.class);
        Account archived = Account.restore(nubank.id(), USER_ID, "Nubank", nubank.type(), "Nubank", nubank.openingBalance(), nubank.openingDate(),
                AccountStatus.ARCHIVED, 3L);
        when(accounts.findById(USER_ID, nubank.id())).thenReturn(Optional.of(new Persisted<>(archived, NOW, NOW)));
        assertThatThrownBy(() -> realize.handle(command("2026-12-10", null, null))).isInstanceOf(AccountArchivedException.class);
        verify(transactions, never()).insert(any());
        verify(ledger, never()).append(any(), any());
    }

    @Test
    @DisplayName("a recorrência de cartão delega ao caso de uso do cartão com o id da movimentação já reservado (para a chave de idempotência)")
    void deveDelegarOCartao() {
        TransactionDetails delegated = mock(TransactionDetails.class);
        when(cardRealization.handle(any())).thenReturn(delegated);
        TransactionDetails result = realize.handle(new RealizeOccurrenceCommand(USER_ID, CARD_RECURRENCE_ID, LocalDate.of(2026, 10, 15),
                Money.of("52.00"), null));
        assertThat(result).isSameAs(delegated);
        ArgumentCaptor<RealizeCardRecurrenceCommand> captor = ArgumentCaptor.forClass(RealizeCardRecurrenceCommand.class);
        verify(cardRealization).handle(captor.capture());
        assertThat(captor.getValue().recurrenceId()).isEqualTo(CARD_RECURRENCE_ID);
        assertThat(captor.getValue().occurrenceDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(captor.getValue().actualAmount()).isEqualTo(Money.of("52.00"));
        assertThat(captor.getValue().transactionId()).isNotNull();
        verify(accounts, never()).lockForUpdate(any(), any());
        assertThat(new RealizeCardRecurrenceCommand(USER_ID, CARD_RECURRENCE_ID, LocalDate.of(2026, 10, 15), null).transactionId()).isNull();
    }

    @Test
    @DisplayName("idempotência: chave nova segue; a mesma chave com o mesmo comando devolve o recurso atual; com outro comando é IDEMPOTENCY_KEY_REUSED")
    void deveSerIdempotente() {
        UUID key = UUID.fromString("00000000-0000-0000-0000-0000000a0001");
        when(idempotency.claim(eq(USER_ID), any(), eq(key), any(), any(), any(), any())).thenReturn(true);
        TransactionDetails first = realize.handle(command("2026-10-10", null, key));
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> resource = ArgumentCaptor.forClass(UUID.class);
        verify(idempotency).claim(eq(USER_ID), any(), eq(key), hash.capture(), resource.capture(), any(), any());
        assertThat(resource.getValue()).isEqualTo(first.transaction().id());

        when(idempotency.claim(eq(USER_ID), any(), eq(key), any(), any(), any(), any())).thenReturn(false);
        when(idempotency.find(USER_ID, "POST /v1/recurrences/{id}/occurrences/{date}/realize", key))
                .thenReturn(Optional.of(new Claim(hash.getValue(), first.transaction().id(), NOW)));
        TransactionDetails replay = realize.handle(command("2026-10-10", null, key));
        assertThat(replay.transaction().id()).isEqualTo(first.transaction().id());
        assertThat(stored).hasSize(1);
        assertThatThrownBy(() -> realize.handle(command("2026-10-10", "130.00", key))).isInstanceOf(IdempotencyKeyReusedException.class);
        when(idempotency.find(any(), any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> realize.handle(command("2026-10-10", null, key))).isInstanceOf(IllegalStateException.class);
        verify(ledger, org.mockito.Mockito.times(1)).append(any(), any());
        verify(transactions, never()).update(any());
    }

    @Test
    @DisplayName("pular: a ocorrência vira SKIPPED sem movimentação; pular de novo, pular a realizada, data errada e outro usuário são recusados")
    void devePular() {
        OccurrenceView skipped = skip.handle(new SkipOccurrenceCommand(USER_ID, RECURRENCE_ID, LocalDate.of(2026, 11, 10)));
        assertThat(skipped.status()).isEqualTo(OccurrenceStatus.SKIPPED);
        assertThat(skipped.date()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(skipped.transactionId()).isNull();
        assertThat(recurrences.findById(USER_ID, RECURRENCE_ID).orElseThrow().skippedDates()).containsExactly(LocalDate.of(2026, 11, 10));
        assertThat(recurrences.locks()).isEqualTo(1);
        assertThat(stored).isEmpty();
        assertThatThrownBy(() -> skip.handle(new SkipOccurrenceCommand(USER_ID, RECURRENCE_ID, LocalDate.of(2026, 11, 10)))).isInstanceOf(OccurrenceSkippedException.class);
        assertThatThrownBy(() -> skip.handle(new SkipOccurrenceCommand(USER_ID, RECURRENCE_ID, LocalDate.of(2026, 11, 11)))).isInstanceOf(NotAnOccurrenceException.class);
        assertThatThrownBy(() -> skip.handle(new SkipOccurrenceCommand(OTHER_USER_ID, RECURRENCE_ID, LocalDate.of(2026, 10, 10)))).isInstanceOf(RecurrenceNotFoundException.class);
        realize.handle(command("2026-10-10", null, null));
        assertThatThrownBy(() -> skip.handle(new SkipOccurrenceCommand(USER_ID, RECURRENCE_ID, LocalDate.of(2026, 10, 10)))).isInstanceOf(OccurrenceAlreadyRealizedException.class);
    }
}
