package com.moneylog.core.sk.page;

import java.util.List;
import java.util.Objects;

/**
 * Página sem {@code COUNT}: itens + se existe próxima página (BE 05, D2 §4).
 */
public record Slice<T>(List<T> items, boolean hasNext) {

    public Slice {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    public static <T> Slice<T> empty() {
        return new Slice<>(List.of(), false);
    }

    /**
     * Monta a página a partir de uma consulta que buscou {@code pageSize + 1} itens: se veio o extra, há próxima
     * página e ele é descartado.
     */
    public static <T> Slice<T> fromOverfetch(List<T> fetched, PageQuery query) {
        Objects.requireNonNull(fetched, "fetched");
        boolean hasNext = fetched.size() > query.pageSize();
        return new Slice<>(hasNext ? fetched.subList(0, query.pageSize()) : fetched, hasNext);
    }
}
