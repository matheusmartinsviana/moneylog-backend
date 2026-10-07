package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.time.YearMonth;
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
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.cards.OngoingPreviewView;
import com.moneylog.core.finance.app.cards.PurchasePreviewView;
import com.moneylog.core.finance.app.cards.PurchasePreviewView.InvoiceTarget;
import com.moneylog.core.finance.app.cards.PurchasePreviewView.Warning;
import com.moneylog.core.finance.app.cards.usecase.CreateOngoingInstallmentsUseCase.CreateOngoingInstallmentsCommand;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.Transaction;

/** Prévia da compra em andamento: monta a mesma compra da criação ({@code plan}) e a descarta. Não trava; roda somente leitura. */
public class PreviewOngoingInstallmentsUseCaseImpl implements PreviewOngoingInstallmentsUseCase {

    private final CardGateway cards;
    private final CategoryGateway categories;
    private final FinancialRecordsGateway records;
    private final IdGenerator ids;
    private final TimeProvider time;

    public PreviewOngoingInstallmentsUseCaseImpl(CardGateway cards, CategoryGateway categories, FinancialRecordsGateway records,
                                                 IdGenerator ids, TimeProvider time) {
        this.cards = cards;
        this.categories = categories;
        this.records = records;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public OngoingPreviewView handle(CreateOngoingInstallmentsCommand c) {
        CreditCard card = cards.findById(c.userId(), c.cardId()).map(Persisted::value).orElseThrow(() -> new CardNotFoundException(c.cardId()));
        FinancialRecords loaded = records.load(c.userId());
        InvoiceChain chain = CardViews.chainOf(card, loaded);
        LocalDate today = time.today();
        Transaction purchase = CreateOngoingInstallmentsUseCaseImpl.plan(c, ids.next(), card,
                CreateOngoingInstallmentsUseCaseImpl.category(categories, c), chain, today);

        List<PurchasePreviewView.Installment> installments = new ArrayList<>();
        for (TransactionInstallment i : purchase.installments()) {
            InvoicePeriod period = chain.calendar().periodClosingIn(YearMonth.from(i.invoiceClosingDate()));
            installments.add(new PurchasePreviewView.Installment(i.number(), i.total(), i.amount(), i.competence(),
                    new InvoiceTarget(InvoiceIds.of(card.id(), i.invoiceClosingDate()), period.referenceMonth(), period.closingDate(),
                            period.dueDate(), chain.statusOf(i.invoiceClosingDate(), today)), i.lateEntry()));
        }
        List<Posting> postings = new ArrayList<>();
        for (LedgerEntry entry : loaded.ledger().entries()) {
            if (entry.posting().bookType() == BookType.CARD && card.id().equals(entry.posting().book().id())) {
                postings.add(entry.posting());
            }
        }
        postings.addAll(purchase.postings().stream().filter(p -> p.bookType() == BookType.CARD).toList());
        AvailableLimit after = AvailableLimitCalculator.calculate(card, postings);
        List<Warning> warnings = new ArrayList<>();
        if (installments.stream().anyMatch(PurchasePreviewView.Installment::lateEntry)) {
            warnings.add(Warning.LATE_ENTRY);
        }
        if (after.overLimit()) {
            warnings.add(Warning.OVER_LIMIT);
        }
        PurchasePreviewView view = new PurchasePreviewView(installments, purchase.amount(), after.available(), after.overLimit(), warnings);
        InvoiceTarget first = installments.getFirst().invoice();
        InvoiceTarget last = installments.getLast().invoice();
        return new OngoingPreviewView(installments.size(), c.installmentAmount(), purchase.amount(), first.dueDate(), last.dueDate(), view);
    }
}
