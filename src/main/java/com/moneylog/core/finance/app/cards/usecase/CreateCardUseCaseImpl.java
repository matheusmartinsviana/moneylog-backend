package com.moneylog.core.finance.app.cards.usecase;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;

public class CreateCardUseCaseImpl implements CreateCardUseCase {

    private final CardGateway cards;
    private final AccountGateway accounts;
    private final FinancialRecordsGateway records;
    private final IdGenerator ids;
    private final TimeProvider time;

    public CreateCardUseCaseImpl(CardGateway cards, AccountGateway accounts, FinancialRecordsGateway records, IdGenerator ids,
                                 TimeProvider time) {
        this.cards = cards;
        this.accounts = accounts;
        this.records = records;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public CardDetails handle(CreateCardCommand command) {
        CardAccounts.requireUsablePaymentAccount(accounts, command.userId(), command.defaultPaymentAccountId());
        CreditCard card = CreditCard.create(ids.next(), command.userId(), command.name(), command.institution(), command.limit(),
                command.closingDay(), command.dueDay(), command.defaultPaymentAccountId(), time.today());
        card.updateProfile(command.profile());
        Persisted<CreditCard> saved = cards.save(card);
        return CardViews.of(saved, records.load(command.userId()), time.today());
    }
}
