package com.moneylog.core.importing.domain;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineTransitionException;

/**
 * Linha em revisão e a decisão do usuário sobre ela (D1 §6). Semente do {@code ImportTransaction} da etapa de
 * importação.
 *
 * <pre>
 * PENDING ────────────accept──▶ ACCEPTED
 *    └────────────────reject──▶ REJECTED
 * POSSIBLE_DUPLICATE ─accept──▶ ACCEPTED   (usuário confirma que é outro lançamento)
 *    └────────────────reject──▶ REJECTED   (é o mesmo lançamento manual)
 * DUPLICATE ──forceImport(lote)▶ ACCEPTED  (forçado: chave derivada, ver abaixo)
 *    └────────────────reject──▶ REJECTED
 * ACCEPTED ─reopen▶ PENDING | POSSIBLE_DUPLICATE | DUPLICATE   (volta ao estado de origem; só com o lote em revisão)
 * ACCEPTED ─reject▶ REJECTED
 * REJECTED é final.
 * </pre>
 *
 * <p><b>Duplicata forçada (S-43/S-75 revisada).</b> A chave original já existe numa movimentação ativa e o banco
 * garante unicidade de {@code (user_id, dedup_key)} entre as ativas. A linha forçada é gravada com
 * {@link #effectiveKey()} = {@code key.forcedIn(lote)} em {@code dedup_key} (única, determinística, e protege contra
 * aplicar o mesmo lote duas vezes) e com {@link #key()} em {@code original_dedup_key}. O {@link DuplicateClassifier}
 * compara a chave da linha com as duas colunas, então reimportar o mesmo arquivo continua marcando a linha como
 * {@code DUPLICATE}, mesmo depois de a movimentação original ser estornada.
 */
public final class ImportLineReview {

    private final ImportLine line;
    private final DedupKey key;
    private final List<UUID> matches;
    private ImportLineStatus status;
    private DedupKey effectiveKey;
    private boolean forced;

    private ImportLineReview(ImportLine line, DedupKey key, ImportLineStatus status, List<UUID> matches) {
        this.line = Objects.requireNonNull(line, "line");
        this.key = Objects.requireNonNull(key, "key");
        this.status = Objects.requireNonNull(status, "status");
        this.matches = List.copyOf(matches);
        this.effectiveKey = key;
    }

    /** Classifica a linha contra as movimentações existentes e abre a revisão dela. */
    public static ImportLineReview classify(ImportLine line, DedupKey key, Collection<ExistingEntry> existing) {
        DuplicateClassifier.Classification classification = DuplicateClassifier.classify(line, key, existing);
        return new ImportLineReview(line, key, classification.status(), classification.matches());
    }

    /** Reconstitui a revisão gravada (persistência): estado, chave efetiva e se a duplicata foi forçada. */
    public static ImportLineReview restore(ImportLine line, DedupKey key, DedupKey effectiveKey, ImportLineStatus status,
                                           List<UUID> matches, boolean forced) {
        ImportLineReview review = new ImportLineReview(line, key, status, matches);
        review.effectiveKey = Objects.requireNonNull(effectiveKey, "effectiveKey");
        review.forced = forced;
        return review;
    }

    /** Aceita uma linha nova ou uma possível duplicata que o usuário confirmou ser outro lançamento. */
    public void accept() {
        if (status != ImportLineStatus.PENDING && status != ImportLineStatus.POSSIBLE_DUPLICATE) {
            throw new InvalidImportLineTransitionException(status, "accept");
        }
        status = ImportLineStatus.ACCEPTED;
    }

    /** Descarta a linha. */
    public void reject() {
        if (status == ImportLineStatus.REJECTED) {
            throw new InvalidImportLineTransitionException(status, "reject");
        }
        if (status == ImportLineStatus.ACCEPTED) {
            resetForce();
        }
        status = ImportLineStatus.REJECTED;
    }

    /**
     * Desfaz a aceitação enquanto o lote está em revisão. Volta ao estado de origem, para a decisão não ser pulada: duplicata forçada volta a
     * {@code DUPLICATE}; linha que nasceu com possível duplicata volta a {@code POSSIBLE_DUPLICATE}; as demais, a {@code PENDING}.
     */
    public void reopen() {
        if (status != ImportLineStatus.ACCEPTED) {
            throw new InvalidImportLineTransitionException(status, "reopen");
        }
        status = forced ? ImportLineStatus.DUPLICATE : matches.isEmpty() ? ImportLineStatus.PENDING : ImportLineStatus.POSSIBLE_DUPLICATE;
        resetForce();
    }

    private void resetForce() {
        effectiveKey = key;
        forced = false;
    }

    /**
     * O usuário decide importar uma linha {@code DUPLICATE} mesmo assim. A movimentação será gravada com uma chave
     * derivada do lote, para não colidir com a original.
     */
    public void forceImport(UUID importBatchId) {
        if (status != ImportLineStatus.DUPLICATE) {
            throw new InvalidImportLineTransitionException(status, "forceImport");
        }
        effectiveKey = key.forcedIn(importBatchId);
        forced = true;
        status = ImportLineStatus.ACCEPTED;
    }

    public ImportLine line() {
        return line;
    }

    /** Chave original da linha (a que detecta duplicatas); vai para {@code original_dedup_key}. */
    public DedupKey key() {
        return key;
    }

    /** Chave a gravar em {@code dedup_key}: a original, ou a derivada do lote se a duplicata foi forçada. */
    public DedupKey effectiveKey() {
        return effectiveKey;
    }

    public ImportLineStatus status() {
        return status;
    }

    public boolean isForced() {
        return forced;
    }

    /** Movimentações existentes que motivaram {@code DUPLICATE}/{@code POSSIBLE_DUPLICATE}. */
    public List<UUID> matches() {
        return matches;
    }
}
