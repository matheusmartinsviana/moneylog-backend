package com.moneylog.core.finance.app.cards.usecase;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.TimeProvider;

public class ListCardsUseCaseImpl implements ListCardsUseCase {

    private final CardGateway cards;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public ListCardsUseCaseImpl(CardGateway cards, FinancialRecordsGateway records, TimeProvider time) {
        this.cards = cards;
        this.records = records;
        this.time = time;
    }

    @Override
    public Slice<CardDetails> handle(ListCardsQuery query) {
        CardStatus status = query.status() == null ? CardStatus.ACTIVE : query.status();
        var page = cards.list(query.userId(), status, query.page());
        if (page.items().isEmpty()) {
            return Slice.empty();
        }
        return new Slice<>(CardViews.ofAll(page.items(), records.load(query.userId()), time.today()), page.hasNext());
    }
}
