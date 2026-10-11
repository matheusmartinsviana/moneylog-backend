package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.AvailableLimit;
import com.moneylog.core.card.domain.AvailableLimitCalculator;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.domain.InvoicePeriod;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.cards.PurchasePreviewView;
import com.moneylog.core.finance.app.cards.PurchasePreviewView.InvoiceTarget;
import com.moneylog.core.finance.app.cards.PurchasePreviewView.Warning;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.transactions.handlers.CardPurchaseHandler;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionOrigin;

public class PreviewCardPurchaseUseCaseImpl implements PreviewCardPurchaseUseCase {

    private final CardGateway cards;
    private final FinancialRecordsGateway records;
    private final IdGenerator ids;
    private final TimeProvider time;

    public PreviewCardPurchaseUseCaseImpl(CardGateway cards, FinancialRecordsGateway records, IdGenerator ids, TimeProvider time) {
        this.cards = cards;
        this.records = records;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public PurchasePreviewView handle(PreviewCardPurchaseQuery query) {
        CreditCard card = cards.findById(query.userId(), query.cardId()).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(query.cardId()));
        FinancialRecords loaded = records.load(query.userId());
        InvoiceChain chain = CardViews.chainOf(card, loaded);
        LocalDate today = time.today();
        Category stand = Category.create(ids.next(), query.userId(), "Prévia", CategoryKind.EXPENSE);
        TransactionHeader header = new TransactionHeader(ids.next(), query.userId(), query.date(), null, null, TransactionOrigin.manual());

        Transaction purchase = CardPurchaseHandler.plan(header, query.amount(), card, stand, query.installments(), chain, today);

        List<PurchasePreviewView.Installment> installments = new ArrayList<>();
        for (TransactionInstallment i : purchase.installments()) {
            InvoicePeriod period = chain.calendar().periodClosingIn(java.time.YearMonth.from(i.invoiceClosingDate()));
            installments.add(new PurchasePreviewView.Installment(i.number(), i.total(), i.amount(), i.competence(),
                    new InvoiceTarget(InvoiceIds.of(card.id(), i.invoiceClosingDate()), period.referenceMonth(), period.closingDate(),
                            period.dueDate(), chain.statusOf(i.invoiceClosingDate(), today)), i.lateEntry()));
        }
        AvailableLimit after = AvailableLimitCalculator.calculate(card, postingsAfter(card, loaded, purchase));
        List<Warning> warnings = new ArrayList<>();
        if (installments.stream().anyMatch(PurchasePreviewView.Installment::lateEntry)) {
            warnings.add(Warning.LATE_ENTRY);
        }
        if (after.overLimit()) {
            warnings.add(Warning.OVER_LIMIT);
        }
        if (query.date().isAfter(today)) {
            warnings.add(Warning.FUTURE_DATE);
        }
        return new PurchasePreviewView(installments, purchase.amount(), after.available(), after.overLimit(), warnings);
    }

    private static List<Posting> postingsAfter(CreditCard card, FinancialRecords loaded, Transaction purchase) {
        List<Posting> postings = new ArrayList<>();
        for (LedgerEntry entry : loaded.ledger().entries()) {
            if (entry.posting().bookType() == BookType.CARD && card.id().equals(entry.posting().book().id())) {
                postings.add(entry.posting());
            }
        }
        postings.addAll(purchase.postings());
        return postings;
    }
}
