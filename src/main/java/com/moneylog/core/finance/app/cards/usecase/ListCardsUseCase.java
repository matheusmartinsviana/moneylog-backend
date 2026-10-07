package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListCardsUseCase {

    /** Ordem {@code name asc}; {@code status} nulo = {@code ACTIVE} (S-126). Números do mesmo carregamento do dashboard (S-79). */
    Slice<CardDetails> handle(ListCardsQuery query);

    record ListCardsQuery(UUID userId, CardStatus status, PageQuery page) {
    }
}
