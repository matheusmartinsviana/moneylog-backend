package com.moneylog.core.finance.app.cards.usecase;

import java.util.UUID;

import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListCardsUseCase {

    Slice<CardDetails> handle(ListCardsQuery query);

    record ListCardsQuery(UUID userId, CardStatus status, PageQuery page) {
    }
}
