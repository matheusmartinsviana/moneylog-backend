package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.exception.CardExceptions.BillingChangeNotConfirmedException;
import com.moneylog.core.card.exception.CardExceptions.CardBillingChangeStaleException;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.CardBillingChanges;
import com.moneylog.core.transaction.domain.Transaction;

public class ChangeCardBillingDaysUseCaseImpl implements ChangeCardBillingDaysUseCase {

    private final CardGateway cards;
    private final AccountGateway accounts;
    private final TransactionGateway transactions;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public ChangeCardBillingDaysUseCaseImpl(CardGateway cards, AccountGateway accounts, TransactionGateway transactions,
                                            FinancialRecordsGateway records, TimeProvider time) {
        this.cards = cards;
        this.accounts = accounts;
        this.transactions = transactions;
        this.records = records;
        this.time = time;
    }

    @Override
    public CardDetails handle(ChangeCardBillingDaysCommand command) {
        transactions.lockAllOfCard(command.userId(), command.cardId());
        cards.lockForUpdate(command.userId(), command.cardId());
        transactions.lockAllOfCard(command.userId(), command.cardId());
        CreditCard card = cards.findById(command.userId(), command.cardId()).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(command.cardId()));
        if (card.version() != command.version()) {
            throw new VersionConflictException(command.version(), card.version());
        }
        LocalDate today = time.today();
        if (command.confirmedEffectiveFrom() == null) {
            BillingChangeSupport.effectiveFromClosingDate(card, command.closingDay(), command.dueDay(), today);
            throw new BillingChangeNotConfirmedException();
        }
        if (!command.confirmedEffectiveFrom().equals(
                BillingChangeSupport.effectiveFromClosingDate(card, command.closingDay(), command.dueDay(), today))) {
            throw new CardBillingChangeStaleException();
        }
        if (!Objects.equals(card.defaultPaymentAccountId(), command.defaultPaymentAccountId())) {
            CardAccounts.requireUsablePaymentAccount(accounts, command.userId(), command.defaultPaymentAccountId());
        }
        List<Transaction> ofCard = transactions.findAllOfCard(command.userId(), card.id());
        card.updateDetails(command.name(), command.institution(), command.limit(), command.defaultPaymentAccountId());
        List<Transaction> changed = CardBillingChanges.changeBillingDays(card, ofCard, command.closingDay(), command.dueDay(), today);
        Persisted<CreditCard> saved = cards.save(card);
        for (Transaction moved : changed) {
            transactions.update(moved);
        }
        cards.refreshInvoiceRows(command.userId(), saved.value());
        return CardViews.of(saved, records.load(command.userId()), today);
    }
}
