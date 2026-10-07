package com.moneylog.core.finance.app.imports;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCase;
import com.moneylog.core.importing.app.ImportBatchView;
import com.moneylog.core.importing.app.gateways.ImportBatchGateway;
import com.moneylog.core.importing.app.gateways.ImportIdempotencyGateway;
import com.moneylog.core.importing.app.gateways.ExistingEntriesGateway;
import com.moneylog.core.importing.domain.ExistingEntry;
import com.moneylog.core.importing.domain.ImportBatch;
import com.moneylog.core.importing.domain.ImportBatchLine;
import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.domain.ImportLineStatus;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportApplyFailedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportBatchNotFoundException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportIdempotencyKeyReusedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportLinesRequireDecisionException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportLineAlreadyRecordedException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportNothingToApplyException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportTransferLegAlreadyRecordedException;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.NotFoundException;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionOrigin;

/** Aplicar o lote (T126): cada linha aceita vira movimentação pelo mesmo caminho da criação manual; qualquer falha desfaz o lote inteiro. */
public class ApplyImportBatchUseCaseImpl implements ApplyImportBatchUseCase {

    static final String DEFAULT_DESCRIPTION = "Lançamento importado";
    private static final Duration IDEMPOTENCY_VALIDITY = Duration.ofHours(24);

    private final ImportBatchGateway batches;
    private final CreateTransactionUseCase create;
    private final ImportIdempotencyGateway idempotency;
    private final TimeProvider time;
    private final ExistingEntriesGateway existing;

    public ApplyImportBatchUseCaseImpl(ImportBatchGateway batches, CreateTransactionUseCase create, ImportIdempotencyGateway idempotency,
                                       TimeProvider time, ExistingEntriesGateway existing) {
        this.existing = existing;
        this.batches = batches;
        this.create = create;
        this.idempotency = idempotency;
        this.time = time;
    }

    @Override
    public ApplyResult handle(ApplyImportBatchCommand command) {
        ImportBatch batch = batches.findForUpdate(command.userId(), command.batchId())
                .orElseThrow(() -> new ImportBatchNotFoundException(command.batchId()));
        if (command.idempotencyKey() != null && alreadyAppliedWithThisKey(command, batch)) {
            return current(command.userId(), batch);
        }
        batch.ensurePendingReview();
        List<ImportBatchLine> lines = batches.allLines(command.userId(), command.batchId());
        long undecided = lines.stream().filter(ImportBatchLine::requiresDecision).count();
        if (undecided > 0) {
            throw new ImportLinesRequireDecisionException((int) undecided);
        }
        List<ImportBatchLine> accepted = lines.stream().filter(l -> l.status() == ImportLineStatus.ACCEPTED).toList();
        if (accepted.isEmpty()) {
            throw new ImportNothingToApplyException();
        }
        guardAgainstDoubleCounting(command.userId(), batch, accepted);
        List<UUID> created = new ArrayList<>();
        for (ImportBatchLine line : accepted) {
            TransactionDetails details;
            try {
                details = create.handleImported(commandFor(command.userId(), batch, line),
                        TransactionOrigin.imported(batch.id(), line.review().effectiveKey(), line.review().key()));
            } catch (BusinessRuleException | NotFoundException | ConflictException e) {
                throw new ImportApplyFailedException(line.position(), e.code(), e.getMessage());
            }
            UUID transactionId = details.transaction().id();
            line.createdAs(transactionId);
            batches.updateLine(command.userId(), line);
            created.add(transactionId);
        }
        batch.markApplied(time.now());
        batches.update(batch);
        return new ApplyResult(new ImportBatchView(batch, batches.counts(command.userId(), batch.id())), created);
    }

    /**
     * Reclassifica sob o lock do lote, com o estado de agora (outro lote pode ter sido aplicado depois da revisão): linha cuja chave já é de uma
     * movimentação ativa (A2) ou transferência cuja outra ponta já é entrada/saída ativa (A1) falha com a posição da linha, sem 500 e sem gravar nada.
     */
    private void guardAgainstDoubleCounting(UUID userId, ImportBatch batch, List<ImportBatchLine> accepted) {
        LocalDate min = accepted.stream().map(l -> l.line().date()).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate max = accepted.stream().map(l -> l.line().date()).max(Comparator.naturalOrder()).orElseThrow();
        List<ExistingEntry> current = existing.findForSource(userId, batch.sourceId(), batch.isCard(), min.minusDays(1), max.plusDays(1));
        for (ImportBatchLine line : accepted) {
            if (current.stream().anyMatch(e -> e.active() && e.hasKey(line.review().effectiveKey()))) {
                var e = new ImportLineAlreadyRecordedException();
                throw new ImportApplyFailedException(line.position(), e.code(), e.getMessage());
            }
            if (line.isTransfer() && existing.hasActiveOppositeLeg(userId, batch.accountId(), line.line().direction(), line.line().amount(),
                    line.line().date())) {
                var e = new ImportTransferLegAlreadyRecordedException();
                throw new ImportApplyFailedException(line.position(), e.code(), e.getMessage());
            }
        }
    }

    /** Mesma chave e mesmo lote já aplicado: devolve o resultado atual. Chave de outro lote: 409. */
    private boolean alreadyAppliedWithThisKey(ApplyImportBatchCommand command, ImportBatch batch) {
        String hash = requestHash(command.batchId());
        Instant now = time.now();
        if (idempotency.claim(command.userId(), IDEMPOTENCY_SCOPE, command.idempotencyKey(), hash, command.batchId(), now,
                now.minus(IDEMPOTENCY_VALIDITY))) {
            return false;
        }
        var existing = idempotency.find(command.userId(), IDEMPOTENCY_SCOPE, command.idempotencyKey());
        if (existing.isEmpty()) {
            return false;
        }
        if (!existing.get().requestHash().equals(hash)) {
            throw new ImportIdempotencyKeyReusedException();
        }
        return batch.status() != com.moneylog.core.importing.domain.ImportBatchStatus.PENDING_REVIEW;
    }

    /** {@code request_hash} é CHAR(64): SHA-256 (hex) do lote, igual nas duas pontas. */
    public static String requestHash(UUID batchId) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("apply|" + batchId).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM", e);
        }
    }

    private ApplyResult current(UUID userId, ImportBatch batch) {
        List<UUID> created = batches.allLines(userId, batch.id()).stream().map(ImportBatchLine::transactionId)
                .filter(java.util.Objects::nonNull).toList();
        return new ApplyResult(new ImportBatchView(batch, batches.counts(userId, batch.id())), created);
    }

    /** O comando da movimentação para a linha: conta → despesa/receita (ou uma transferência decidida); cartão → compra à vista. */
    static CreateTransactionCommand commandFor(UUID userId, ImportBatch batch, ImportBatchLine line) {
        String text = line.descriptionOverride() != null ? line.descriptionOverride() : line.line().description();
        if (text == null || text.isBlank()) {
            text = DEFAULT_DESCRIPTION;
        }
        text = text.strip();
        if (text.length() > TransactionHeader.DESCRIPTION_MAX_LENGTH) {
            text = text.substring(0, TransactionHeader.DESCRIPTION_MAX_LENGTH).strip();
        }
        Common common = new Common(userId, line.line().amount(), line.line().date(), text, null);
        if (batch.isCard()) {
            return new CreateTransactionCommand.CardPurchase(common, batch.cardId(), line.categoryId(), 1);
        }
        UUID account = batch.accountId();
        if (line.isTransfer()) {
            return line.line().direction() == ImportDirection.OUT
                    ? new CreateTransactionCommand.Transfer(common, account, line.counterpartAccountId())
                    : new CreateTransactionCommand.Transfer(common, line.counterpartAccountId(), account);
        }
        PaymentMethod method = parse(line.method());
        return line.line().direction() == ImportDirection.OUT
                ? new CreateTransactionCommand.Expense(common, account, line.categoryId(), method)
                : new CreateTransactionCommand.Income(common, account, line.categoryId(), method);
    }

    private static PaymentMethod parse(String method) {
        if (method == null) {
            return PaymentMethod.OTHER;
        }
        try {
            return PaymentMethod.valueOf(method);
        } catch (IllegalArgumentException e) {
            return PaymentMethod.OTHER;
        }
    }
}
