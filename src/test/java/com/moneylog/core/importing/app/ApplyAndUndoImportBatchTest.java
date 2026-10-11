package com.moneylog.core.importing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.moneylog.core.finance.app.imports.ApplyImportBatchUseCase.ApplyImportBatchCommand;
import com.moneylog.core.finance.app.imports.ApplyImportBatchUseCase.ApplyResult;
import com.moneylog.core.finance.app.imports.ApplyImportBatchUseCaseImpl;
import com.moneylog.core.finance.app.imports.UndoImportBatchUseCase.UndoImportBatchCommand;
import com.moneylog.core.finance.app.imports.UndoImportBatchUseCaseImpl;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCase;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCase;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCase.VoidTransactionCommand;
import com.moneylog.core.importing.app.gateways.ExistingEntriesGateway;
import com.moneylog.core.importing.app.gateways.ImportBatchGateway;
import com.moneylog.core.importing.app.gateways.ImportIdempotencyGateway;
import com.moneylog.core.importing.domain.CategorySuggester.Confidence;
import com.moneylog.core.importing.domain.CategorySuggester.Suggestion;
import com.moneylog.core.importing.domain.DedupKey;
import com.moneylog.core.importing.domain.ExistingEntry;
import com.moneylog.core.importing.domain.ImportBatch;
import com.moneylog.core.importing.domain.ImportBatchLine;
import com.moneylog.core.importing.domain.ImportBatchStatus;
import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.domain.ImportLine;
import com.moneylog.core.importing.domain.ImportLineFlag;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportApplyFailedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportBatchNotPendingException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportBatchNotUndoableException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportLinesRequireDecisionException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportNothingToApplyException;
import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.domain.TransactionSource;

@DisplayName("Aplicar e desfazer o lote (T126)")
class ApplyAndUndoImportBatchTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NUBANK = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID XP = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID CARD = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID BATCH = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID FOOD = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final LocalDate D = LocalDate.of(2026, 10, 10);
    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");

    private ImportBatchGateway batches;
    private CreateTransactionUseCase create;
    private ImportIdempotencyGateway idempotency;
    private ExistingEntriesGateway existing;
    private VoidTransactionUseCase voids;
    private ApplyImportBatchUseCaseImpl apply;
    private UndoImportBatchUseCaseImpl undo;
    private int sequence;

    @BeforeEach
    void setUp() {
        batches = mock(ImportBatchGateway.class);
        create = mock(CreateTransactionUseCase.class);
        idempotency = mock(ImportIdempotencyGateway.class);
        voids = mock(VoidTransactionUseCase.class);
        ClockTimeProvider time = new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC), ZoneId.of("America/Sao_Paulo"));
        existing = mock(ExistingEntriesGateway.class);
        apply = new ApplyImportBatchUseCaseImpl(batches, create, idempotency, time, existing);
        undo = new UndoImportBatchUseCaseImpl(batches, voids, time);
        sequence = 0;
        when(batches.counts(any(), any())).thenReturn(new ImportBatchGateway.Counts(0, 0, 0, 0, 0, 0, 0));
        when(create.handleImported(any(), any())).thenAnswer(inv -> {
            TransactionDetails details = mock(TransactionDetails.class);
            Transaction tx = mock(Transaction.class);
            when(tx.id()).thenReturn(new UUID(8, ++sequence));
            when(details.transaction()).thenReturn(tx);
            return details;
        });
    }

    private ImportBatch batch(UUID account, UUID card) {
        ImportBatch batch = ImportBatch.open(BATCH, USER, account, card, "CSV", "a".repeat(64), NOW, List.of(), List.of());
        when(batches.findForUpdate(USER, BATCH)).thenReturn(Optional.of(batch));
        when(batches.find(USER, BATCH)).thenReturn(Optional.of(batch));
        return batch;
    }

    private ImportBatchLine line(UUID source, int position, String amount, ImportDirection direction, String description, Set<ImportLineFlag> flags,
                                 List<ExistingEntry> existing) {
        ImportLine line = ImportLine.of(source, D, Money.of(amount), direction, description);
        return ImportBatchLine.open(new UUID(6, position), BATCH, position, line, DedupKey.of(line, 1), existing, flags,
                new Suggestion(FOOD, "PIX", Confidence.HIGH));
    }

    private void lines(ImportBatchLine... lines) {
        when(batches.allLines(USER, BATCH)).thenReturn(List.of(lines));
    }

    @Test
    @DisplayName("conta: aceitas viram EXPENSE/INCOME pelos mesmos handlers (origem IMPORT, lote e chaves); rejeitadas não; lote vira APPLIED")
    void aplicaConta() {
        ImportBatch batch = batch(NUBANK, null);
        ImportBatchLine expense = line(NUBANK, 1, "82.90", ImportDirection.OUT, "Ifood", Set.of(), List.of());
        ImportBatchLine income = line(NUBANK, 2, "5000.00", ImportDirection.IN, "Salário", Set.of(), List.of());
        ImportBatchLine rejected = line(NUBANK, 3, "10.00", ImportDirection.OUT, "Ignorar", Set.of(), List.of());
        expense.accept(null, null, "Ifood Restaurante");
        income.accept(FOOD, "TRANSFER", null);
        rejected.reject();
        lines(expense, income, rejected);

        ApplyResult result = apply.handle(new ApplyImportBatchCommand(USER, BATCH, null));

        ArgumentCaptor<CreateTransactionCommand> commands = ArgumentCaptor.forClass(CreateTransactionCommand.class);
        ArgumentCaptor<TransactionOrigin> origins = ArgumentCaptor.forClass(TransactionOrigin.class);
        verify(create, times(2)).handleImported(commands.capture(), origins.capture());
        CreateTransactionCommand.Expense e = (CreateTransactionCommand.Expense) commands.getAllValues().get(0);
        assertThat(e.accountId()).isEqualTo(NUBANK);
        assertThat(e.categoryId()).isEqualTo(FOOD);
        assertThat(e.method()).isEqualTo(PaymentMethod.PIX);
        assertThat(e.common().description()).isEqualTo("Ifood Restaurante");
        assertThat(e.common().amount()).isEqualTo(Money.of("82.90"));
        assertThat(e.common().userId()).isEqualTo(USER);
        CreateTransactionCommand.Income i = (CreateTransactionCommand.Income) commands.getAllValues().get(1);
        assertThat(i.method()).isEqualTo(PaymentMethod.TRANSFER);
        assertThat(origins.getAllValues()).allSatisfy(o -> {
            assertThat(o.source()).isEqualTo(TransactionSource.IMPORT);
            assertThat(o.importBatchId()).isEqualTo(BATCH);
        });
        assertThat(origins.getAllValues().get(0).originalDedupKey()).isEqualTo(expense.review().key());
        assertThat(result.createdTransactionIds()).containsExactly(new UUID(8, 1), new UUID(8, 2));
        assertThat(batch.status()).isEqualTo(ImportBatchStatus.APPLIED);
        verify(batches).update(batch);
        verify(batches).updateLine(USER, expense);
        assertThat(expense.transactionId()).isEqualTo(new UUID(8, 1));
    }

    @Test
    @DisplayName("sem método escolhido, conta usa OTHER")
    void metodoPadrao() {
        batch(NUBANK, null);
        ImportBatchLine l = ImportBatchLine.open(new UUID(6, 1), BATCH, 1, ImportLine.of(NUBANK, D, Money.of("1.00"), ImportDirection.OUT, "x"),
                DedupKey.of(ImportLine.of(NUBANK, D, Money.of("1.00"), ImportDirection.OUT, "x"), 1), List.of(), Set.of(), null);
        l.accept(FOOD, null, null);
        lines(l);
        apply.handle(new ApplyImportBatchCommand(USER, BATCH, null));
        ArgumentCaptor<CreateTransactionCommand> commands = ArgumentCaptor.forClass(CreateTransactionCommand.class);
        verify(create).handleImported(commands.capture(), any());
        assertThat(((CreateTransactionCommand.Expense) commands.getValue()).method()).isEqualTo(PaymentMethod.OTHER);
        assertThat(((CreateTransactionCommand.Expense) commands.getValue()).common().description()).isEqualTo("x");
    }

    @Test
    @DisplayName("cartão: compra à vista (1 parcela) pela alocação normal da fatura; pagamento de fatura nunca vira movimentação (S-168)")
    void aplicaCartao() {
        batch(null, CARD);
        ImportBatchLine purchase = line(CARD, 1, "50.00", ImportDirection.OUT, "Mercado", Set.of(), List.of());
        ImportBatchLine payment = line(CARD, 2, "1283.00", ImportDirection.IN, "PAGAMENTO RECEBIDO", Set.of(ImportLineFlag.LOOKS_LIKE_INVOICE_PAYMENT), List.of());
        purchase.accept(null, null, null);
        lines(purchase, payment);

        apply.handle(new ApplyImportBatchCommand(USER, BATCH, null));

        ArgumentCaptor<CreateTransactionCommand> commands = ArgumentCaptor.forClass(CreateTransactionCommand.class);
        verify(create, times(1)).handleImported(commands.capture(), any());
        CreateTransactionCommand.CardPurchase p = (CreateTransactionCommand.CardPurchase) commands.getValue();
        assertThat(p.cardId()).isEqualTo(CARD);
        assertThat(p.installments()).isEqualTo(1);
    }

    @Test
    @DisplayName("transferência decidida: UMA TRANSFER; saída vai da conta do lote à outra, entrada faz o caminho inverso")
    void transferencia() {
        batch(NUBANK, null);
        ImportBatchLine out = line(NUBANK, 1, "1000.00", ImportDirection.OUT, "TED", Set.of(ImportLineFlag.POSSIBLE_TRANSFER), List.of());
        ImportBatchLine in = line(NUBANK, 2, "700.00", ImportDirection.IN, "TED recebida", Set.of(ImportLineFlag.POSSIBLE_TRANSFER), List.of());
        out.acceptAsTransfer(XP);
        in.acceptAsTransfer(XP);
        lines(out, in);

        apply.handle(new ApplyImportBatchCommand(USER, BATCH, null));

        ArgumentCaptor<CreateTransactionCommand> commands = ArgumentCaptor.forClass(CreateTransactionCommand.class);
        verify(create, times(2)).handleImported(commands.capture(), any());
        CreateTransactionCommand.Transfer first = (CreateTransactionCommand.Transfer) commands.getAllValues().get(0);
        CreateTransactionCommand.Transfer second = (CreateTransactionCommand.Transfer) commands.getAllValues().get(1);
        assertThat(first.fromAccountId()).isEqualTo(NUBANK);
        assertThat(first.toAccountId()).isEqualTo(XP);
        assertThat(second.fromAccountId()).isEqualTo(XP);
        assertThat(second.toAccountId()).isEqualTo(NUBANK);
    }

    @Test
    @DisplayName("A1: transferência cuja outra ponta já é despesa/receita ativa: IMPORT_APPLY_FAILED, nada é criado")
    void transferenciaComPernaAtiva() {
        batch(NUBANK, null);
        ImportBatchLine in = line(NUBANK, 1, "700.00", ImportDirection.IN, "TED recebida", Set.of(ImportLineFlag.POSSIBLE_TRANSFER), List.of());
        in.acceptAsTransfer(XP);
        lines(in);
        when(existing.hasActiveOppositeLeg(any(), any(), any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> apply.handle(new ApplyImportBatchCommand(USER, BATCH, null)))
                .isInstanceOfSatisfying(ImportApplyFailedException.class, e -> assertThat(e.causeCode()).isEqualTo("IMPORT_TRANSFER_LEG_ALREADY_RECORDED"));
        verify(create, never()).handleImported(any(), any());
    }

    @Test
    @DisplayName("A2: o mesmo extrato já aplicado em outro lote: IMPORT_APPLY_FAILED com a posição, nada é criado")
    void mesmoExtratoEmOutroLote() {
        batch(NUBANK, null);
        ImportBatchLine l = line(NUBANK, 3, "10.00", ImportDirection.OUT, "Padaria", Set.of(), List.of());
        l.accept(FOOD, null, null);
        lines(l);
        ExistingEntry active = new ExistingEntry(new UUID(2, 2), NUBANK, D, l.line().amount(), l.line().direction(), l.review().key(), null, false, true);
        when(existing.findForSource(any(), any(), anyBoolean(), any(), any())).thenReturn(List.of(active));

        assertThatThrownBy(() -> apply.handle(new ApplyImportBatchCommand(USER, BATCH, null)))
                .isInstanceOfSatisfying(ImportApplyFailedException.class, e -> {
                    assertThat(e.position()).isEqualTo(3);
                    assertThat(e.causeCode()).isEqualTo("IMPORT_LINE_ALREADY_RECORDED");
                });
        verify(create, never()).handleImported(any(), any());
    }

    @Test
    @DisplayName("duplicata forçada grava a chave derivada do lote e guarda a original")
    void duplicataForcada() {
        batch(NUBANK, null);
        ImportLine raw = ImportLine.of(NUBANK, D, Money.of("10.00"), ImportDirection.OUT, "Padaria");
        ExistingEntry same = new ExistingEntry(new UUID(2, 2), NUBANK, D, raw.amount(), raw.direction(), DedupKey.of(raw, 1), null, false, true);
        ImportBatchLine forced = line(NUBANK, 1, "10.00", ImportDirection.OUT, "Padaria", Set.of(), List.of(same));
        forced.force(BATCH, null, null);
        lines(forced);
        apply.handle(new ApplyImportBatchCommand(USER, BATCH, null));
        ArgumentCaptor<TransactionOrigin> origin = ArgumentCaptor.forClass(TransactionOrigin.class);
        verify(create).handleImported(any(), origin.capture());
        assertThat(origin.getValue().dedupKey()).isEqualTo(forced.review().key().forcedIn(BATCH));
        assertThat(origin.getValue().originalDedupKey()).isEqualTo(forced.review().key());
    }

    @Test
    @DisplayName("linha ainda pedindo decisão: IMPORT_LINES_REQUIRE_DECISION, nada é criado")
    void exigeDecisao() {
        batch(NUBANK, null);
        lines(line(NUBANK, 1, "1000.00", ImportDirection.OUT, "TED", Set.of(ImportLineFlag.POSSIBLE_TRANSFER), List.of()),
                line(NUBANK, 2, "10.00", ImportDirection.OUT, "Padaria", Set.of(), List.of()));
        assertThatThrownBy(() -> apply.handle(new ApplyImportBatchCommand(USER, BATCH, null))).isInstanceOf(ImportLinesRequireDecisionException.class);
        verify(create, never()).handleImported(any(), any());
    }

    @Test
    @DisplayName("lote sem linha aceita: erro; lote já aplicado: IMPORT_BATCH_NOT_PENDING")
    void vazioEJaAplicado() {
        ImportBatch batch = batch(NUBANK, null);
        ImportBatchLine rejected = line(NUBANK, 1, "10.00", ImportDirection.OUT, "x", Set.of(), List.of());
        rejected.reject();
        lines(rejected);
        assertThatThrownBy(() -> apply.handle(new ApplyImportBatchCommand(USER, BATCH, null))).isInstanceOf(ImportNothingToApplyException.class);
        batch.markApplied(NOW);
        assertThatThrownBy(() -> apply.handle(new ApplyImportBatchCommand(USER, BATCH, null))).isInstanceOf(ImportBatchNotPendingException.class);
    }

    @Test
    @DisplayName("falha numa linha: IMPORT_APPLY_FAILED aponta a linha e o lote NÃO é marcado aplicado (a transação desfaz o resto)")
    void falhaDesfazTudo() {
        ImportBatch batch = batch(NUBANK, null);
        ImportBatchLine first = line(NUBANK, 1, "10.00", ImportDirection.OUT, "a", Set.of(), List.of());
        ImportBatchLine second = line(NUBANK, 2, "20.00", ImportDirection.OUT, "b", Set.of(), List.of());
        first.accept(FOOD, null, null);
        second.accept(FOOD, null, null);
        lines(first, second);
        when(create.handleImported(any(), any())).thenThrow(new InvalidImportLineException("categoria arquivada")).thenAnswer(inv -> null);

        assertThatThrownBy(() -> apply.handle(new ApplyImportBatchCommand(USER, BATCH, null)))
                .isInstanceOfSatisfying(ImportApplyFailedException.class, e -> assertThat(e.position()).isEqualTo(1));
        assertThat(batch.status()).isEqualTo(ImportBatchStatus.PENDING_REVIEW);
        verify(batches, never()).update(any());
    }

    @Test
    @DisplayName("Idempotency-Key repetida em lote já aplicado devolve o mesmo resultado, sem criar de novo")
    void idempotencia() {
        ImportBatch batch = batch(NUBANK, null);
        batch.markApplied(NOW);
        UUID key = new UUID(5, 5);
        ImportBatchLine created = line(NUBANK, 1, "10.00", ImportDirection.OUT, "a", Set.of(), List.of());
        created.accept(FOOD, null, null);
        created.createdAs(new UUID(8, 1));
        lines(created);
        when(idempotency.claim(eq(USER), any(), eq(key), eq(ApplyImportBatchUseCaseImpl.requestHash(BATCH)), eq(BATCH), any(), any())).thenReturn(false);
        when(idempotency.find(eq(USER), any(), eq(key))).thenReturn(Optional.of(new ImportIdempotencyGateway.Claim(ApplyImportBatchUseCaseImpl.requestHash(BATCH), BATCH)));

        ApplyResult result = apply.handle(new ApplyImportBatchCommand(USER, BATCH, key));

        assertThat(result.createdTransactionIds()).containsExactly(new UUID(8, 1));
        verify(create, never()).handleImported(any(), any());
    }

    @Test
    @DisplayName("desfazer estorna as movimentações ativas do lote com o motivo e o lote vira UNDONE")
    void desfaz() {
        ImportBatch batch = batch(NUBANK, null);
        batch.markApplied(NOW);
        UUID t1 = new UUID(8, 1);
        UUID t2 = new UUID(8, 2);
        when(batches.activeTransactionIds(USER, BATCH)).thenReturn(List.of(t1, t2));

        var result = undo.handle(new UndoImportBatchCommand(USER, BATCH, "arquivo errado"));

        ArgumentCaptor<VoidTransactionCommand> voided = ArgumentCaptor.forClass(VoidTransactionCommand.class);
        verify(voids, times(2)).handle(voided.capture());
        assertThat(voided.getAllValues()).extracting(VoidTransactionCommand::transactionId).containsExactly(t1, t2);
        assertThat(voided.getAllValues().get(0).reason()).isEqualTo("importação desfeita: arquivo errado");
        assertThat(result.blocked()).isEmpty();
        assertThat(batch.status()).isEqualTo(ImportBatchStatus.UNDONE);
        assertThat(batch.undoReason()).isEqualTo("arquivo errado");
        verify(batches).update(batch);
    }

    @Test
    @DisplayName("guarda recusa um estorno (compra em fatura fechada): fica em blocked, os outros são estornados, lote PARTIALLY_UNDONE")
    void parcial() {
        ImportBatch batch = batch(NUBANK, null);
        batch.markApplied(NOW);
        UUID t1 = new UUID(8, 1);
        UUID t2 = new UUID(8, 2);
        when(batches.activeTransactionIds(USER, BATCH)).thenReturn(List.of(t1, t2));
        doThrow(new InvalidImportLineException("TRANSACTION_IN_CLOSED_INVOICE")).when(voids).handle(new VoidTransactionCommand(USER, t1,
                "importação desfeita"));

        var result = undo.handle(new UndoImportBatchCommand(USER, BATCH, null));

        assertThat(result.blocked()).hasSize(1);
        assertThat(result.blocked().get(0).transactionId()).isEqualTo(t1);
        assertThat(result.blocked().get(0).code()).isEqualTo("IMPORT_LINE_INVALID");
        verify(voids).handle(new VoidTransactionCommand(USER, t2, "importação desfeita"));
        assertThat(batch.status()).isEqualTo(ImportBatchStatus.PARTIALLY_UNDONE);
    }

    @Test
    @DisplayName("lote em revisão não se desfaz")
    void naoAplicado() {
        batch(NUBANK, null);
        assertThatThrownBy(() -> undo.handle(new UndoImportBatchCommand(USER, BATCH, null))).isInstanceOf(ImportBatchNotUndoableException.class);
        verify(voids, never()).handle(any());
    }
}
