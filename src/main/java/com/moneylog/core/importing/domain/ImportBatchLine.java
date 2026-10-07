package com.moneylog.core.importing.domain;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.importing.domain.CategorySuggester.Suggestion;
import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineException;
import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineTransitionException;

/**
 * Linha de um lote em revisão: a {@link ImportLineReview} (estado e chaves) mais o que a revisão acrescenta (marcas, sugestão, escolhas do usuário).
 * Sem setters públicos; toda mudança é um método de negócio que respeita as transições do {@link ImportLineReview}.
 *
 * <p>O que exige decisão (nunca é aceito em lote nem aplicado sem escolha): {@code POSSIBLE_DUPLICATE} e as marcas
 * {@link ImportLineFlag#requiresDecision()} enquanto a linha está {@code PENDING}. Transferência: {@link #acceptAsTransfer} cria <b>uma</b> TRANSFER
 * na aplicação; a perna espelhada do outro extrato fica {@code POSSIBLE_DUPLICATE} dela e continua pedindo decisão (conservador, PR 01).
 */
public final class ImportBatchLine {

    public static final String REASON_INVOICE_PAYMENT = "Pagamento de fatura: já registrado como pagamento na conta.";

    public static final String REASON_CARD_CREDIT = "Crédito no cartão (estorno ou pagamento): não é compra. Registre o reembolso à parte.";

    private final UUID id;
    private final UUID batchId;
    private final int position;
    private final ImportLineReview review;
    private final Set<ImportLineFlag> flags;
    private final Suggestion suggestion;
    private String rejectReason;
    private UUID categoryId;
    private String method;
    private String descriptionOverride;
    private UUID counterpartAccountId;
    private boolean learn;
    private UUID transactionId;

    private ImportBatchLine(UUID id, UUID batchId, int position, ImportLineReview review, Collection<ImportLineFlag> flags,
                            Suggestion suggestion) {
        this.id = Objects.requireNonNull(id, "id");
        this.batchId = Objects.requireNonNull(batchId, "batchId");
        this.position = position;
        this.review = Objects.requireNonNull(review, "review");
        this.flags = flags.isEmpty() ? EnumSet.noneOf(ImportLineFlag.class) : EnumSet.copyOf(flags);
        this.suggestion = suggestion;
    }

    /** Linha nova: classifica contra o que já existe e aplica as marcas (pagamento de fatura nasce rejeitada). */
    public static ImportBatchLine open(UUID id, UUID batchId, int position, ImportLine line, DedupKey key, Collection<ExistingEntry> existing,
                                       Collection<ImportLineFlag> flags, Suggestion suggestion) {
        ImportBatchLine result = new ImportBatchLine(id, batchId, position, ImportLineReview.classify(line, key, existing), flags, suggestion);
        if (result.flags.contains(ImportLineFlag.LOOKS_LIKE_INVOICE_PAYMENT)) {
            result.review.reject();
            result.rejectReason = REASON_INVOICE_PAYMENT;
        } else if (result.flags.contains(ImportLineFlag.CARD_CREDIT_NOT_IMPORTED)) {
            result.review.reject();
            result.rejectReason = REASON_CARD_CREDIT;
        }
        return result;
    }

    /** Reconstitui a linha gravada. */
    @SuppressWarnings("java:S107")
    public static ImportBatchLine restore(UUID id, UUID batchId, int position, ImportLineReview review, Collection<ImportLineFlag> flags,
                                          Suggestion suggestion, String rejectReason, UUID categoryId, String method,
                                          String descriptionOverride, UUID counterpartAccountId, boolean learn, UUID transactionId) {
        ImportBatchLine line = new ImportBatchLine(id, batchId, position, review, flags, suggestion);
        line.rejectReason = rejectReason;
        line.categoryId = categoryId;
        line.method = method;
        line.descriptionOverride = descriptionOverride;
        line.counterpartAccountId = counterpartAccountId;
        line.learn = learn;
        line.transactionId = transactionId;
        return line;
    }

    public boolean requiresDecision() {
        ImportLineStatus status = review.status();
        if (status == ImportLineStatus.POSSIBLE_DUPLICATE) {
            return true;
        }
        return status == ImportLineStatus.PENDING && LineFlagger.requiresDecision(flags);
    }

    /**
     * Aceita a linha como entrada/saída comum. Sem categoria própria usa a sugerida; sem nenhuma das duas é erro (a categoria é obrigatória na
     * movimentação).
     */
    public void accept(UUID newCategoryId, String newMethod, String newDescription) {
        UUID resolved = newCategoryId != null ? newCategoryId : categoryId != null ? categoryId : suggestion != null ? suggestion.categoryId() : null;
        if (resolved == null) {
            throw new InvalidImportLineException("escolha uma categoria para aceitar a linha.");
        }
        review.accept();
        this.categoryId = resolved;
        this.method = newMethod != null ? newMethod : method != null ? method : suggestion != null ? suggestion.method() : null;
        if (newDescription != null && !newDescription.isBlank()) {
            this.descriptionOverride = newDescription.strip();
        }
    }

    /** Decide que a linha marcada {@code POSSIBLE_TRANSFER} é uma transferência com a conta informada: uma única TRANSFER na aplicação. */
    public void acceptAsTransfer(UUID counterpartAccount) {
        if (!flags.contains(ImportLineFlag.POSSIBLE_TRANSFER) || review.status() != ImportLineStatus.PENDING) {
            throw new InvalidImportLineTransitionException(review.status(), "asTransfer");
        }
        if (counterpartAccount == null || counterpartAccount.equals(review.line().sourceId())) {
            throw new InvalidImportLineException("a conta da transferência precisa ser outra conta do usuário.");
        }
        review.accept();
        this.counterpartAccountId = counterpartAccount;
        this.categoryId = null;
    }

    public void reject() {
        review.reject();
        this.rejectReason = null;
        this.counterpartAccountId = null;
    }

    /** Desfaz a aceitação (enquanto o lote está em revisão): a linha volta a pedir a decisão de origem. */
    public void reopen() {
        review.reopen();
        this.counterpartAccountId = null;
    }

    /** Importa mesmo sendo duplicata; a movimentação sai com a chave derivada do lote. */
    public void force(UUID importBatchId, UUID newCategoryId, String newMethod) {
        review.forceImport(importBatchId);
        UUID resolved = newCategoryId != null ? newCategoryId : suggestion != null ? suggestion.categoryId() : null;
        if (resolved == null) {
            throw new InvalidImportLineException("escolha uma categoria para forçar a linha.");
        }
        this.categoryId = resolved;
        this.method = newMethod != null ? newMethod : suggestion != null ? suggestion.method() : null;
    }

    public void learnFromReview() {
        this.learn = true;
    }

    public void createdAs(UUID createdTransactionId) {
        this.transactionId = Objects.requireNonNull(createdTransactionId, "createdTransactionId");
    }

    public boolean isTransfer() {
        return counterpartAccountId != null;
    }

    public UUID id() {
        return id;
    }

    public UUID batchId() {
        return batchId;
    }

    public int position() {
        return position;
    }

    public ImportLineReview review() {
        return review;
    }

    public ImportLine line() {
        return review.line();
    }

    public ImportLineStatus status() {
        return review.status();
    }

    public Set<ImportLineFlag> flags() {
        return Set.copyOf(flags);
    }

    public Suggestion suggestion() {
        return suggestion;
    }

    public String rejectReason() {
        return rejectReason;
    }

    public UUID categoryId() {
        return categoryId;
    }

    public String method() {
        return method;
    }

    public String descriptionOverride() {
        return descriptionOverride;
    }

    public UUID counterpartAccountId() {
        return counterpartAccountId;
    }

    public boolean learn() {
        return learn;
    }

    public UUID transactionId() {
        return transactionId;
    }
}
