package com.moneylog.infra.web;

import java.util.List;
import java.util.function.Function;

import com.moneylog.core.sk.page.Slice;

public record SliceResponse<T>(List<T> items, boolean hasNext) {

    public SliceResponse {
        items = List.copyOf(items);
    }

    public static <S, T> SliceResponse<T> of(Slice<S> slice, Function<S, T> mapper) {
        return new SliceResponse<>(slice.items().stream().map(mapper).toList(), slice.hasNext());
    }
}
