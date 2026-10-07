package com.moneylog.core.recurrence.app;

import java.util.List;

import com.moneylog.core.sk.page.Slice;

/**
 * Página de ocorrências (mesmos {@code items}/{@code hasNext} de {@link Slice}) e o aviso de que as ocorrências <b>vencidas e
 * pendentes</b> foram limitadas às mais recentes (S-240).
 */
public record OccurrenceListing(List<OccurrenceView> items, boolean hasNext, boolean overdueTruncated) {

    public OccurrenceListing {
        items = List.copyOf(items);
    }

    public static OccurrenceListing of(Slice<OccurrenceView> slice, boolean overdueTruncated) {
        return new OccurrenceListing(slice.items(), slice.hasNext(), overdueTruncated);
    }
}
