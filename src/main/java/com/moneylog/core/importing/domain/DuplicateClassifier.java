package com.moneylog.core.importing.domain;

import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Classifica uma linha importada contra as movimentações existentes (D1 §6, S-25):
 * <ol>
 *   <li>{@code DUPLICATE}: alguma movimentação <b>ativa</b> tem a mesma chave, como chave efetiva ({@code dedup_key})
 *       <b>ou</b> como chave original ({@code original_dedup_key}). A segunda cobre a duplicata forçada, gravada com a
 *       chave derivada do lote (S-75 revisada): mesmo com a original estornada, reimportar o arquivo não cria outra
 *       cópia;</li>
 *   <li>{@code POSSIBLE_DUPLICATE}: alguma movimentação <b>manual ativa</b> tem a mesma conta/cartão, o mesmo valor, a
 *       mesma direção e data a no máximo 1 dia de distância;</li>
 *   <li>{@code PENDING}: caso contrário.</li>
 * </ol>
 * Movimentações estornadas não contam.
 */
public final class DuplicateClassifier {

    /** Janela de data para possível duplicata com lançamento manual (S-25). */
    public static final long POSSIBLE_DUPLICATE_WINDOW_DAYS = 1;

    private DuplicateClassifier() {
    }

    public static Classification classify(ImportLine line, DedupKey key, Collection<ExistingEntry> existing) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(existing, "existing");

        List<UUID> sameKey = existing.stream()
                .filter(ExistingEntry::active)
                .filter(e -> e.hasKey(key))
                .map(ExistingEntry::transactionId)
                .toList();
        if (!sameKey.isEmpty()) {
            return new Classification(ImportLineStatus.DUPLICATE, sameKey);
        }

        List<UUID> similarManual = existing.stream()
                .filter(ExistingEntry::active)
                .filter(ExistingEntry::manual)
                .filter(e -> e.sourceId().equals(line.sourceId()))
                .filter(e -> e.direction() == line.direction())
                .filter(e -> e.amount().equals(line.amount()))
                .filter(e -> Math.abs(ChronoUnit.DAYS.between(e.date(), line.date())) <= POSSIBLE_DUPLICATE_WINDOW_DAYS)
                .map(ExistingEntry::transactionId)
                .toList();
        if (!similarManual.isEmpty()) {
            return new Classification(ImportLineStatus.POSSIBLE_DUPLICATE, similarManual);
        }

        return new Classification(ImportLineStatus.PENDING, List.of());
    }

    /**
     * Resultado da classificação.
     *
     * @param status  {@code DUPLICATE}, {@code POSSIBLE_DUPLICATE} ou {@code PENDING}
     * @param matches movimentações existentes que motivaram o status (vazio em {@code PENDING})
     */
    public record Classification(ImportLineStatus status, List<UUID> matches) {
        public Classification {
            Objects.requireNonNull(status, "status");
            matches = List.copyOf(matches);
        }
    }
}
