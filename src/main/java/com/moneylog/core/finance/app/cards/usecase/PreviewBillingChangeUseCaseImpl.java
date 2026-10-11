package com.moneylog.core.finance.app.cards.usecase;

import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.finance.app.cards.BillingChangePreviewView;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.Transaction;

import java.util.List;

public class PreviewBillingChangeUseCaseImpl implements PreviewBillingChangeUseCase {

    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public PreviewBillingChangeUseCaseImpl(FinancialRecordsGateway records, TimeProvider time) {
        this.records = records;
        this.time = time;
    }

    @Override
    public BillingChangePreviewView handle(PreviewBillingChangeQuery query) {
        FinancialRecords loaded = records.load(query.userId());
        CreditCard card = loaded.cards().stream().filter(c -> c.id().equals(query.cardId())).findFirst()
                .orElseThrow(() -> new CardNotFoundException(query.cardId()));
        List<Transaction> ofCard = loaded.transactions().stream().filter(t -> card.id().equals(t.cardId())).toList();
        return BillingChangeSupport.preview(card, ofCard, query.closingDay(), query.dueDay(), time.today());
    }
}
