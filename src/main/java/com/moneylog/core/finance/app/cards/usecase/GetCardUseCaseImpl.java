package com.moneylog.core.finance.app.cards.usecase;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;

public class GetCardUseCaseImpl implements GetCardUseCase {

    private final CardGateway cards;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public GetCardUseCaseImpl(CardGateway cards, FinancialRecordsGateway records, TimeProvider time) {
        this.cards = cards;
        this.records = records;
        this.time = time;
    }

    @Override
    public CardDetails handle(GetCardQuery query) {
        Persisted<CreditCard> card = cards.findById(query.userId(), query.cardId())
                .orElseThrow(() -> new CardNotFoundException(query.cardId()));
        return CardViews.of(card, records.load(query.userId()), time.today());
    }
}
