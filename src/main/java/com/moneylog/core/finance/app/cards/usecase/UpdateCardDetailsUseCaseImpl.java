package com.moneylog.core.finance.app.cards.usecase;

import java.util.Objects;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;

public class UpdateCardDetailsUseCaseImpl implements UpdateCardDetailsUseCase {

    private final CardGateway cards;
    private final AccountGateway accounts;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;
    private final ChangeCardBillingDaysUseCase billingDays;

    public UpdateCardDetailsUseCaseImpl(CardGateway cards, AccountGateway accounts, FinancialRecordsGateway records,
                                        TimeProvider time, ChangeCardBillingDaysUseCase billingDays) {
        this.cards = cards;
        this.accounts = accounts;
        this.records = records;
        this.time = time;
        this.billingDays = billingDays;
    }

    @Override
    public CardDetails handle(UpdateCardDetailsCommand command) {
        // a leitura sem trava só decide o caminho: mudar os dias exige travar as movimentações antes do cartão (outra ordem)
        CardGateway.BillingDays current = cards.currentBillingDays(command.userId(), command.cardId())
                .orElseThrow(() -> new CardNotFoundException(command.cardId()));
        if (current.closingDay() != command.closingDay() || current.dueDay() != command.dueDay()) {
            return billingDays.handle(new ChangeCardBillingDaysUseCase.ChangeCardBillingDaysCommand(command.userId(), command.cardId(),
                    command.name(), command.institution(), command.limit(), command.closingDay(), command.dueDay(),
                    command.defaultPaymentAccountId(), command.confirmedEffectiveFrom(), command.version()));
        }
        cards.lockForUpdate(command.userId(), command.cardId());
        CreditCard card = cards.findById(command.userId(), command.cardId()).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(command.cardId()));
        if (card.version() != command.version()) {
            throw new VersionConflictException(command.version(), card.version());
        }
        // só valida a conta se ela mudou: editar o nome não pode falhar porque a conta antiga foi arquivada depois
        if (!Objects.equals(card.defaultPaymentAccountId(), command.defaultPaymentAccountId())) {
            CardAccounts.requireUsablePaymentAccount(accounts, command.userId(), command.defaultPaymentAccountId());
        }
        card.updateDetails(command.name(), command.institution(), command.limit(), command.defaultPaymentAccountId());
        Persisted<CreditCard> saved = cards.save(card);
        return CardViews.of(saved, records.load(command.userId()), time.today());
    }
}
