package com.moneylog.core.importing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryKindMismatchException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.importing.app.gateways.ExistingEntriesGateway;
import com.moneylog.core.importing.app.gateways.ImportBatchGateway;
import com.moneylog.core.importing.app.gateways.ImportHistoryGateway;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.Action;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.BulkResult;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.BulkReviewCommand;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.ReviewImportLineCommand;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCasesImpl;
import com.moneylog.core.importing.domain.CategorySuggester.Confidence;
import com.moneylog.core.importing.domain.CategorySuggester.Suggestion;
import com.moneylog.core.importing.domain.DedupKey;
import com.moneylog.core.importing.domain.ImportBatch;
import com.moneylog.core.importing.domain.ImportBatchLine;
import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.domain.ImportLine;
import com.moneylog.core.importing.domain.ImportLineFlag;
import com.moneylog.core.importing.domain.ImportLineStatus;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportBatchNotFoundException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportBatchNotPendingException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportLineNotFoundException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportTransferLegAlreadyRecordedException;
import com.moneylog.core.sk.money.Money;

@DisplayName("Revisão das linhas (T125)")
class ReviewImportLinesTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NUBANK = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID XP = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID BATCH = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID FOOD = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    private ImportBatchGateway batches;
    private ImportHistoryGateway history;
    private ExistingEntriesGateway existing;
    private CategoryGateway categories;
    private ReviewImportLinesUseCasesImpl.ReviewLine review;
    private ReviewImportLinesUseCasesImpl.BulkReview bulk;
    private ImportBatch batch;

    @BeforeEach
    void setUp() {
        batches = mock(ImportBatchGateway.class);
        history = mock(ImportHistoryGateway.class);
        existing = mock(ExistingEntriesGateway.class);
        categories = mock(CategoryGateway.class);
        when(categories.findById(USER, FOOD)).thenReturn(Optional.of(Category.create(FOOD, USER, "Comida", CategoryKind.EXPENSE)));
        review = new ReviewImportLinesUseCasesImpl.ReviewLine(batches, history, () -> new UUID(0, 99), existing, categories);
        bulk = new ReviewImportLinesUseCasesImpl.BulkReview(batches);
        batch = ImportBatch.open(BATCH, USER, NUBANK, null, "CSV", "a".repeat(64), Instant.parse("2026-10-10T12:00:00Z"), List.of(), List.of());
        when(batches.findForUpdate(USER, BATCH)).thenReturn(Optional.of(batch));
    }

    private ImportBatchLine pending(String description, Set<ImportLineFlag> flags, boolean suggested) {
        ImportLine line = ImportLine.of(NUBANK, LocalDate.of(2026, 10, 10), Money.of("10.00"), ImportDirection.OUT, description);
        ImportBatchLine l = ImportBatchLine.open(UUID.randomUUID(), BATCH, 1, line, DedupKey.of(line, 1), List.of(), flags,
                suggested ? new Suggestion(FOOD, "PIX", Confidence.HIGH) : null);
        when(batches.findLine(USER, BATCH, l.id())).thenReturn(Optional.of(l));
        return l;
    }

    private ReviewImportLineCommand command(UUID lineId, Action action, UUID category, UUID counterpart, boolean learn) {
        return new ReviewImportLineCommand(USER, BATCH, lineId, action, category, null, null, counterpart, learn);
    }

    @Test
    @DisplayName("ACCEPT com categoria vira ACCEPTED e grava; REJECT vira REJECTED")
    void aceitarRejeitar() {
        ImportBatchLine a = pending("Padaria", Set.of(), false);
        ImportBatchLine r = pending("Mercado", Set.of(), false);
        assertThat(review.handle(command(a.id(), Action.ACCEPT, FOOD, null, false)).status()).isEqualTo(ImportLineStatus.ACCEPTED);
        assertThat(review.handle(command(r.id(), Action.REJECT, null, null, false)).status()).isEqualTo(ImportLineStatus.REJECTED);
        verify(batches).updateLine(USER, a);
        verify(batches).updateLine(USER, r);
    }

    @Test
    @DisplayName("learn grava uma regra (descrição normalizada + direção → categoria)")
    void aprender() {
        ImportBatchLine l = pending("Posto Shell", Set.of(), false);
        review.handle(command(l.id(), Action.ACCEPT, FOOD, null, true));
        verify(history).upsertRule(USER, new UUID(0, 99), ImportDirection.OUT, "POSTO SHELL", FOOD, null);
    }

    @Test
    @DisplayName("asTransfer marca a linha como transferência e exige a marca POSSIBLE_TRANSFER")
    void transferencia() {
        ImportBatchLine t = pending("TED", Set.of(ImportLineFlag.POSSIBLE_TRANSFER), false);
        ImportBatchLine out = review.handle(command(t.id(), Action.ACCEPT, null, XP, false));
        assertThat(out.isTransfer()).isTrue();
        assertThat(out.counterpartAccountId()).isEqualTo(XP);
        ImportBatchLine plain = pending("Padaria", Set.of(), true);
        assertThatThrownBy(() -> review.handle(command(plain.id(), Action.ACCEPT, null, XP, false))).hasMessageContaining("asTransfer");
    }

    @Test
    @DisplayName("lote já aplicado: IMPORT_BATCH_NOT_PENDING; lote/linha inexistentes: 404")
    void estados() {
        ImportBatchLine l = pending("Padaria", Set.of(), true);
        batch.markApplied(Instant.parse("2026-10-10T13:00:00Z"));
        assertThatThrownBy(() -> review.handle(command(l.id(), Action.ACCEPT, null, null, false))).isInstanceOf(ImportBatchNotPendingException.class);
        verify(batches, never()).updateLine(any(), any());
        assertThatThrownBy(() -> review.handle(new ReviewImportLineCommand(USER, UUID.randomUUID(), l.id(), Action.REJECT, null, null, null, null, false)))
                .isInstanceOf(ImportBatchNotFoundException.class);
        ImportBatch open = ImportBatch.open(new UUID(4, 4), USER, NUBANK, null, "CSV", "a".repeat(64), Instant.now(), List.of(), List.of());
        when(batches.findForUpdate(USER, open.id())).thenReturn(Optional.of(open));
        assertThatThrownBy(() -> review.handle(new ReviewImportLineCommand(USER, open.id(), UUID.randomUUID(), Action.REJECT, null, null, null, null,
                false))).isInstanceOf(ImportLineNotFoundException.class);
    }

    @Test
    @DisplayName("lote: só atualiza as linhas sem marca de decisão; as demais voltam em skipped com o motivo")
    void emLote() {
        ImportBatchLine ok = pending("Padaria", Set.of(), true);
        ImportBatchLine transfer = pending("TED", Set.of(ImportLineFlag.POSSIBLE_TRANSFER), true);
        ImportBatchLine noCategory = pending("Desconhecido", Set.of(), false);
        UUID missing = UUID.randomUUID();
        when(batches.findLine(USER, BATCH, missing)).thenReturn(Optional.empty());

        BulkResult result = bulk.handle(new BulkReviewCommand(USER, BATCH, List.of(ok.id(), transfer.id(), noCategory.id(), missing), Action.ACCEPT));

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.skipped()).extracting(s -> s.reason()).containsExactly("REQUIRES_DECISION", "NO_CATEGORY", "LINE_NOT_FOUND");
        assertThat(ok.status()).isEqualTo(ImportLineStatus.ACCEPTED);
        assertThat(transfer.status()).isEqualTo(ImportLineStatus.PENDING);
        verify(batches).updateLine(USER, ok);
        verify(batches, never()).updateLine(USER, transfer);
    }

    @Test
    @DisplayName("lote: FORCE não existe em lote; REJECT em lote funciona")
    void loteForceRejeitar() {
        ImportBatchLine l = pending("Padaria", Set.of(), true);
        assertThatThrownBy(() -> bulk.handle(new BulkReviewCommand(USER, BATCH, List.of(l.id()), Action.FORCE))).hasMessageContaining("lote");
        assertThat(bulk.handle(new BulkReviewCommand(USER, BATCH, List.of(l.id()), Action.REJECT)).updated()).isEqualTo(1);
    }

    @Test
    @DisplayName("A1: A aplicado (saída virou despesa), depois B como transferência: recusa, não grava e a linha segue pedindo decisão")
    void transferenciaComPernaJaAtiva() {
        ImportBatchLine in = pending("TED recebida", Set.of(ImportLineFlag.POSSIBLE_TRANSFER), false);
        when(existing.hasActiveOppositeLeg(any(), any(), any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> review.handle(command(in.id(), Action.ACCEPT, null, XP, false)))
                .isInstanceOf(ImportTransferLegAlreadyRecordedException.class);

        verify(batches, never()).updateLine(any(), any());
        assertThat(review.handle(command(in.id(), Action.REJECT, null, null, false)).status()).isEqualTo(ImportLineStatus.REJECTED);
    }

    @Test
    @DisplayName("M2: categoria inexistente/de outro usuário é 404 e de tipo errado é 422, antes de gravar ou aprender regra")
    void categoriaInvalida() {
        ImportBatchLine l = pending("Padaria", Set.of(), false);
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> review.handle(command(l.id(), Action.ACCEPT, other, null, true))).isInstanceOf(CategoryNotFoundException.class);
        UUID income = UUID.randomUUID();
        when(categories.findById(USER, income)).thenReturn(Optional.of(Category.create(income, USER, "Salário", CategoryKind.INCOME)));
        assertThatThrownBy(() -> review.handle(command(l.id(), Action.ACCEPT, income, null, true))).isInstanceOf(CategoryKindMismatchException.class);
        verify(history, never()).upsertRule(any(), any(), any(), any(), any(), any());
        verify(batches, never()).updateLine(any(), any());
    }

    @Test
    @DisplayName("A2: REOPEN desfaz a aceitação (volta a PENDING); REJECT vale em linha aceita; em lote REOPEN não existe")
    void reabrir() {
        ImportBatchLine l = pending("Padaria", Set.of(), true);
        review.handle(command(l.id(), Action.ACCEPT, FOOD, null, false));
        assertThat(review.handle(command(l.id(), Action.REOPEN, null, null, false)).status()).isEqualTo(ImportLineStatus.PENDING);
        review.handle(command(l.id(), Action.ACCEPT, FOOD, null, false));
        assertThat(review.handle(command(l.id(), Action.REJECT, null, null, false)).status()).isEqualTo(ImportLineStatus.REJECTED);
        assertThatThrownBy(() -> bulk.handle(new BulkReviewCommand(USER, BATCH, List.of(l.id()), Action.REOPEN))).hasMessageContaining("lote");
    }
}
