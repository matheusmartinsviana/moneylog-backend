package com.moneylog.core.finance.app.cards.usecase;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;

public class SetClosingConfirmationUseCaseImpl implements SetClosingConfirmationUseCase {

    private final CardGateway cards;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public SetClosingConfirmationUseCaseImpl(CardGateway cards, FinancialRecordsGateway records, TimeProvider time) {
        this.cards = cards;
        this.records = records;
        this.time = time;
    }

    @Override
    public CardDetails handle(SetClosingConfirmationCommand c) {
        cards.lockForUpdate(c.userId(), c.cardId());
        Persisted<CreditCard> current = cards.findById(c.userId(), c.cardId()).orElseThrow(() -> new CardNotFoundException(c.cardId()));
        CreditCard card = current.value();
        if (card.version() != c.version()) {
            throw new VersionConflictException(c.version(), card.version());
        }
        card.markClosingDayConfirmed(c.confirmed());
        Persisted<CreditCard> saved = cards.save(card);
        return CardViews.of(saved, records.load(c.userId()), time.today());
    }
}
