package com.moneylog.core.transaction.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.importing.domain.DedupKey;
import com.moneylog.core.transaction.exception.TransactionExceptions.InvalidTransactionOriginException;

/**
 * Como a movimentação nasceu (D1 §2.4): manual, importação (lote + chaves) ou realização de recorrência.
 *
 * @param dedupKey         chave efetiva, que participa da unicidade entre ativas; na duplicata forçada é a chave derivada do lote (S-75)
 * @param originalDedupKey chave original da linha importada (sempre presente na importação), para a reimportação
 *                         continuar detectando a duplicata mesmo depois de forçada
 */
public record TransactionOrigin(TransactionSource source, UUID importBatchId, DedupKey dedupKey, DedupKey originalDedupKey,
                                UUID recurrenceId, LocalDate occurrenceDate) {

    private static final TransactionOrigin MANUAL = new TransactionOrigin(TransactionSource.MANUAL, null, null, null, null, null);

    public TransactionOrigin {
        if (source == null) {
            throw new InvalidTransactionOriginException("a origem é obrigatória.");
        }
        boolean imported = source == TransactionSource.IMPORT;
        if (imported != (importBatchId != null) || imported != (originalDedupKey != null) || (!imported && dedupKey != null)) {
            throw new InvalidTransactionOriginException("lote e chave só existem (e são obrigatórios) na importação.");
        }
        boolean recurrence = source == TransactionSource.RECURRENCE;
        if (recurrence != (recurrenceId != null) || recurrence != (occurrenceDate != null)) {
            throw new InvalidTransactionOriginException("recorrência e data da ocorrência só existem (e são obrigatórias) na realização.");
        }
    }

    public static TransactionOrigin manual() {
        return MANUAL;
    }

    /** Linha importada. {@code effectiveKey} é a chave derivada do lote quando a duplicata foi forçada (S-75). */
    public static TransactionOrigin imported(UUID importBatchId, DedupKey effectiveKey, DedupKey originalKey) {
        return new TransactionOrigin(TransactionSource.IMPORT, importBatchId, effectiveKey, originalKey, null, null);
    }

    public static TransactionOrigin ongoing() {
        return new TransactionOrigin(TransactionSource.ONGOING, null, null, null, null, null);
    }

    public static TransactionOrigin historical() {
        return new TransactionOrigin(TransactionSource.HISTORICAL, null, null, null, null, null);
    }

    public static TransactionOrigin recurrence(UUID recurrenceId, LocalDate occurrenceDate) {
        return new TransactionOrigin(TransactionSource.RECURRENCE, null, null, null, recurrenceId, occurrenceDate);
    }
}
