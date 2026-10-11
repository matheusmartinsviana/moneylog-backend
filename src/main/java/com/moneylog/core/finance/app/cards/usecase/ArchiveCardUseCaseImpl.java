package com.moneylog.core.finance.app.cards.usecase;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.exception.CardExceptions.CardHasPendingBalanceException;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;

public class ArchiveCardUseCaseImpl implements ArchiveCardUseCase {

    private final CardGateway cards;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public ArchiveCardUseCaseImpl(CardGateway cards, FinancialRecordsGateway records, TimeProvider time) {
        this.cards = cards;
        this.records = records;
        this.time = time;
    }

    @Override
    public CardDetails handle(ArchiveCardCommand command) {
        cards.lockForUpdate(command.userId(), command.cardId());
        Persisted<CreditCard> current = cards.findById(command.userId(), command.cardId())
                .orElseThrow(() -> new CardNotFoundException(command.cardId()));
        CreditCard card = current.value();
        if (card.status() == CardStatus.ACTIVE) {
            CardDetails before = CardViews.of(current, records.load(command.userId()), time.today());
            if (!before.archivable()) {
                throw new CardHasPendingBalanceException();
            }
        }
        card.archive();
        Persisted<CreditCard> saved = cards.save(card);
        return CardViews.of(saved, records.load(command.userId()), time.today());
    }
}
