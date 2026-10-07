package com.moneylog.core.importing.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

/**
 * Visão mínima de uma movimentação já registrada, usada só para classificar linhas importadas. Na Etapa 2 o gateway
 * monta esta lista a partir das movimentações da mesma conta/cartão perto da data da linha.
 *
 * @param transactionId id da movimentação
 * @param sourceId      conta ou cartão
 * @param date          data da movimentação
 * @param amount        valor positivo
 * @param direction     entrada ou saída, do ponto de vista da conta/cartão
 * @param dedupKey         chave efetiva gravada ({@code dedup_key}; só movimentações importadas têm), ou {@code null}.
 *                         Na duplicata forçada é a chave derivada do lote (S-75 revisada)
 * @param originalDedupKey chave original da linha importada ({@code original_dedup_key}), ou {@code null}. Na duplicata
 *                         forçada é diferente de {@code dedupKey} e é por ela que a reimportação reconhece a linha
 * @param manual        {@code true} se foi digitada pelo usuário (origem {@code MANUAL})
 * @param active        {@code false} se foi estornada; estornadas não contam como duplicata
 */
public record ExistingEntry(UUID transactionId, UUID sourceId, LocalDate date, Money amount, ImportDirection direction,
                            DedupKey dedupKey, DedupKey originalDedupKey, boolean manual, boolean active) {

    public ExistingEntry {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(direction, "direction");
    }

    /** {@code true} se a chave da linha é a efetiva ou a original desta movimentação. */
    public boolean hasKey(DedupKey key) {
        return key.equals(dedupKey) || key.equals(originalDedupKey);
    }
}
