package com.moneylog.core.sk.persistence;

import java.time.Instant;
import java.util.Objects;

/**
 * Agregado lido ou gravado junto com a {@code version} do lock otimista e os carimbos de tempo, para os agregados cujo
 * domínio não guarda versão (pessoa, dívida, recorrência). A versão é a da linha gravada: quem edita compara a que o
 * cliente mandou com esta e a persistência incrementa ao gravar.
 */
public record Versioned<T>(T value, long version, Instant createdAt, Instant updatedAt) {

    public Versioned {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
