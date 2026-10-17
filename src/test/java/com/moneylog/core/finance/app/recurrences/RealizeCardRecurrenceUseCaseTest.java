package com.moneylog.core.finance.app.recurrences;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.finance.app.recurrences.usecase.RealizeCardRecurrenceUseCase.RealizeCardRecurrenceCommand;
import com.moneylog.core.finance.app.recurrences.usecase.RealizeCardRecurrenceUseCaseImpl;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.finance.domain.CommitmentKind;
import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.finance.domain.Horizon;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.NotAnOccurrenceException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceAlreadyRealizedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceSkippedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceReferenceMismatchException;
import com.moneylog.core.recurrence.util.InMemoryRecurrenceGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionType;

@DisplayName("Realizar ocorrência de recorrência no cartão (T60)")
class RealizeCardRecurrenceUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final LocalDate OCT_15 = LocalDate.of(2026, 10, 15);
    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);
    private static final UUID RECURRENCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000ec001");

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private final TransactionGateway transactions = mock(TransactionGateway.class);
    private final LedgerWriteGateway ledger = mock(LedgerWriteGateway.class);
    private final CardGateway cards = mock(CardGateway.class);
    private final CategoryGateway categories = mock(CategoryGateway.class);
    private final AccountGateway accounts = mock(AccountGateway.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final InMemoryRecurrenceGateway recurrences = new InMemoryRecurrenceGateway(() -> scenario.transactions);
    private final List<UUID> nextIds = new ArrayList<>();
    private RecurringTransaction subscription;
    private RealizeCardRecurrenceUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        subscription = RecurringTransaction.ofCard(RECURRENCE_ID, USER_ID, Money.of("49.90"), "Streaming", scenario.alimentacao.id(),
                scenario.roxinho.id(), Schedule.of(Frequency.MONTHLY, SEP_15));
        recurrences.save(subscription);
        when(time.today()).thenReturn(TODAY);
        when(time.now()).thenReturn(NOW);
        when(ids.next()).thenAnswer(call -> {
            UUID id = UUID.fromString(String.format("00000000-0000-0000-0000-0000000ec1%02d", nextIds.size()));
            nextIds.add(id);
            return id;
        });
        when(cards.findById(USER_ID, scenario.roxinho.id())).thenReturn(Optional.of(new Persisted<>(scenario.roxinho, NOW, NOW)));
        when(categories.findById(USER_ID, scenario.alimentacao.id())).thenReturn(Optional.of(scenario.alimentacao));
        when(transactions.findAllOfCard(USER_ID, scenario.roxinho.id())).thenAnswer(call -> List.copyOf(scenario.transactions));
        when(transactions.insert(any())).thenAnswer(call -> {
            Transaction inserted = call.getArgument(0);
            scenario.transactions.add(inserted);
            return new Persisted<>(inserted, NOW, NOW);
        });
        useCase = new RealizeCardRecurrenceUseCaseImpl(recurrences, cards, categories, transactions, ledger,
                new TransactionDetailsAssembler(transactions, accounts, categories, cards, time), ids, time);
        when(transactions.sumActiveRefundsOf(any(), any())).thenReturn(Money.of("0.00"));
    }

    private TransactionDetails realize(LocalDate date, String actual) {
        return useCase.handle(new RealizeCardRecurrenceCommand(USER_ID, RECURRENCE_ID, date, actual == null ? null : Money.of(actual)));
    }

    private Money commitmentsOut() {
        RecurringTransaction current = recurrences.findById(USER_ID, RECURRENCE_ID).orElseThrow();
        return CommitmentSchedule.of(scenario.records(), List.of(current), TODAY).committed(Horizon.M12);
    }

    @Test
    @DisplayName("CA1: a ocorrência de 15/10 vira uma compra de 49,90 em 1 parcela na fatura que fecha 25/10 (ref. 2026-11), com source RECURRENCE e o par gravado; o compromisso RECURRENCE de 15/10 some e o valor passa a vir só da fatura")
    void deveGerarACompraNaFaturaDoCiclo() {
        Money before = commitmentsOut();

        TransactionDetails details = realize(OCT_15, null);

        Transaction purchase = details.transaction();
        assertThat(purchase.type()).isEqualTo(TransactionType.CARD_PURCHASE);
        assertThat(purchase.amount()).isEqualTo(Money.of("49.90"));
        assertThat(purchase.installments()).singleElement().satisfies(i -> {
            assertThat(i.total()).isEqualTo(1);
            assertThat(i.invoiceClosingDate()).isEqualTo(LocalDate.of(2026, 10, 25));
            assertThat(i.lateEntry()).isFalse();
        });
        assertThat(details.cardInfo().installments().getFirst().invoiceReferenceMonth().toString()).isEqualTo("2026-11");
        assertThat(purchase.origin().source()).isEqualTo(TransactionSource.RECURRENCE);
        assertThat(purchase.origin().recurrenceId()).isEqualTo(RECURRENCE_ID);
        assertThat(purchase.origin().occurrenceDate()).isEqualTo(OCT_15);
        assertThat(purchase.date()).isEqualTo(OCT_15);
        assertThat(purchase.description()).isEqualTo("Streaming");
        assertThat(details.impact().cash()).isEqualTo(Money.of("0.00"));
        CommitmentSchedule schedule = CommitmentSchedule.of(scenario.records(), List.of(recurrences.findById(USER_ID, RECURRENCE_ID).orElseThrow()), TODAY);
        assertThat(schedule.items().stream().filter(c -> c.kind() == CommitmentKind.RECURRENCE).map(c -> c.dueDate()))
                .doesNotContain(OCT_15).contains(LocalDate.of(2026, 11, 15));
        assertThat(schedule.items().stream().filter(c -> c.kind() == CommitmentKind.INVOICE_FORECAST).findFirst().orElseThrow().amount())
                .isEqualTo(Money.of("49.90"));
        assertThat(commitmentsOut()).isEqualTo(before);
    }

    @Test
    @DisplayName("CA2: com o valor real de 52,30 a compra tem 52,30 e a ocorrência deixa de ser prevista")
    void deveUsarOValorReal() {
        TransactionDetails details = realize(OCT_15, "52.30");

        assertThat(details.transaction().amount()).isEqualTo(Money.of("52.30"));
        assertThat(recurrences.findById(USER_ID, RECURRENCE_ID).orElseThrow().occurrences(OCT_15, OCT_15)).isEmpty();
        assertThat(recurrences.findById(USER_ID, RECURRENCE_ID).orElseThrow().realizedDates()).containsExactly(OCT_15);
    }

    @Test
    @DisplayName("CA3: ocorrência de 15/09 com a fatura de 25/09 (vence 05/10) PAID vai para a corrente com lançamento tardio; com a fatura fechada e não paga entra nela como lançamento tardio; nunca em fatura PAID")
    void deveAlocarPeloStatusDaFatura() {
        TransactionDetails toCurrent = realize(SEP_15, null);
        assertThat(toCurrent.transaction().installments().getFirst().lateEntry()).isTrue();
        assertThat(toCurrent.transaction().installments().getFirst().invoiceClosingDate()).isEqualTo(LocalDate.of(2026, 10, 25));

        scenario.transactions.clear();
        scenario.transactions.add(com.moneylog.core.transaction.util.factory.TransactionTestFactory.compraNoCalendario(
                scenario.header(LocalDate.of(2026, 9, 1), "Antiga"), Money.of("100.00"), scenario.roxinho, scenario.alimentacao, 1));
        assertThat(scenario.chain().statusOf(LocalDate.of(2026, 9, 25), TODAY)).isEqualTo(InvoiceStatus.OVERDUE);

        TransactionDetails late = realize(SEP_15, null);

        assertThat(late.transaction().installments().getFirst().invoiceClosingDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(late.transaction().installments().getFirst().lateEntry()).isTrue();
        scenario.pagarFatura("149.90", LocalDate.of(2026, 9, 25), TODAY);
        recurrences.save(RecurringTransaction.ofCard(UUID.fromString("00000000-0000-0000-0000-0000000ec002"), USER_ID, Money.of("10.00"), "Outra",
                scenario.alimentacao.id(), scenario.roxinho.id(), Schedule.of(Frequency.MONTHLY, SEP_15)));
        TransactionDetails afterPaid = useCase.handle(new RealizeCardRecurrenceCommand(USER_ID, UUID.fromString("00000000-0000-0000-0000-0000000ec002"),
                SEP_15, null));
        assertThat(afterPaid.transaction().installments().getFirst().invoiceClosingDate()).isEqualTo(LocalDate.of(2026, 10, 25));
    }

    @Test
    @DisplayName("CA4: já realizada, pulada, data que não é ocorrência, cartão/categoria diferentes do modelo e modelo de conta: RECURRENCE_*; inexistente: RECURRENCE_NOT_FOUND; nada é gravado")
    void deveRecusarOQueNaoPode() {
        realize(OCT_15, null);
        org.mockito.Mockito.clearInvocations(transactions, ledger);
        subscription = recurrences.findById(USER_ID, RECURRENCE_ID).orElseThrow();
        subscription.skip(LocalDate.of(2026, 11, 15));
        recurrences.save(subscription);

        assertThatThrownBy(() -> realize(OCT_15, null)).isInstanceOf(OccurrenceAlreadyRealizedException.class)
                .hasFieldOrPropertyWithValue("code", "RECURRENCE_OCCURRENCE_ALREADY_REALIZED");
        assertThatThrownBy(() -> realize(LocalDate.of(2026, 11, 15), null)).isInstanceOf(OccurrenceSkippedException.class)
                .hasFieldOrPropertyWithValue("code", "RECURRENCE_OCCURRENCE_SKIPPED");
        assertThatThrownBy(() -> realize(LocalDate.of(2026, 10, 16), null)).isInstanceOf(NotAnOccurrenceException.class)
                .hasFieldOrPropertyWithValue("code", "RECURRENCE_NOT_AN_OCCURRENCE");
        assertThatThrownBy(() -> useCase.handle(new RealizeCardRecurrenceCommand(USER_ID, UUID.randomUUID(), OCT_15, null)))
                .isInstanceOf(RecurrenceNotFoundException.class);

        UUID accountModel = UUID.fromString("00000000-0000-0000-0000-0000000ec003");
        recurrences.save(RecurringTransaction.ofAccount(accountModel, USER_ID, TransactionType.EXPENSE, Money.of("30.00"), "Internet",
                scenario.alimentacao.id(), scenario.nubank.id(), com.moneylog.core.transaction.domain.PaymentMethod.PIX,
                Schedule.of(Frequency.MONTHLY, SEP_15)));
        assertThatThrownBy(() -> useCase.handle(new RealizeCardRecurrenceCommand(USER_ID, accountModel, OCT_15, null)))
                .isInstanceOf(RecurrenceReferenceMismatchException.class).hasFieldOrPropertyWithValue("code", "RECURRENCE_REFERENCE_MISMATCH");
        UUID otherCategory = scenario.salario.id();
        recurrences.save(RecurringTransaction.ofCard(UUID.fromString("00000000-0000-0000-0000-0000000ec004"), USER_ID, Money.of("10.00"), "X",
                otherCategory, scenario.roxinho.id(), Schedule.of(Frequency.MONTHLY, SEP_15)));
        when(categories.findById(USER_ID, otherCategory)).thenReturn(Optional.of(scenario.alimentacao));
        assertThatThrownBy(() -> useCase.handle(new RealizeCardRecurrenceCommand(USER_ID, UUID.fromString("00000000-0000-0000-0000-0000000ec004"),
                OCT_15, null))).isInstanceOf(RecurrenceReferenceMismatchException.class);

        verify(transactions, never()).insert(any());
        verify(ledger, never()).append(any(), anyList());
    }

    @Test
    @DisplayName("CA5: a compra gerada, estornada, devolve a ocorrência às pendentes e ela pode ser realizada de novo (S-49: unicidade só entre ativas)")
    void devePoderRealizarDeNovoDepoisDoEstorno() {
        Transaction first = realize(OCT_15, null).transaction();
        assertThatThrownBy(() -> realize(OCT_15, null)).isInstanceOf(OccurrenceAlreadyRealizedException.class);

        scenario.estornar(first, "realizei sem querer", TODAY);

        TransactionDetails again = realize(OCT_15, "50.00");
        assertThat(again.transaction().id()).isNotEqualTo(first.id());
        assertThat(again.transaction().amount()).isEqualTo(Money.of("50.00"));
        assertThat(scenario.transactions.stream().filter(t -> t.isActive() && t.origin().source() == TransactionSource.RECURRENCE)).hasSize(1);
    }

    @Test
    @DisplayName("CA6: grava a compra e depois os lançamentos balanceados (EXPENSE +49,90 / CARD −49,90), travando a recorrência antes do cartão e lendo a cadeia só depois")
    void deveGravarNaOrdemCerta() {
        realize(OCT_15, null);

        InOrder order = inOrder(cards, transactions, ledger);
        order.verify(cards).lockForUpdate(USER_ID, scenario.roxinho.id());
        order.verify(transactions).findAllOfCard(USER_ID, scenario.roxinho.id());
        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        order.verify(transactions).insert(saved.capture());
        ArgumentCaptor<List<LedgerEntry>> entries = ArgumentCaptor.forClass(List.class);
        order.verify(ledger).append(org.mockito.ArgumentMatchers.eq(USER_ID), entries.capture());
        assertThat(recurrences.locks()).isEqualTo(1);
        assertThat(entries.getValue()).isEqualTo(saved.getValue().entries());
        assertThat(entries.getValue().stream().map(e -> e.posting().bookType()).toList()).containsExactlyInAnyOrder(BookType.EXPENSE, BookType.CARD);
        assertThat(entries.getValue().stream().mapToLong(e -> e.posting().amount().cents()).sum()).isZero();
        assertThat(saved.getValue().origin().recurrenceId()).isEqualTo(RECURRENCE_ID);
    }
}
