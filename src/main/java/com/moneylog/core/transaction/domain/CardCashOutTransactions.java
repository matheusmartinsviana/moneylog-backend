package com.moneylog.core.transaction.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InstallmentPlan;
import com.moneylog.core.card.domain.InvoiceAllocator;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.CardCashOutExceptions.CardCashOutInvalidException;

public final class CardCashOutTransactions {

    private CardCashOutTransactions() {
    }

    public static Transaction toAccount(TransactionHeader header, CardCashOutPlan plan, LocalDate firstInvoiceDate, CreditCard card,
                                        Account account, Category interestCategory, InvoiceChain chain, LocalDate today) {
        AccountTransactions.requireCashAccount(header, plan.principal(), account, TransactionType.CARD_PURCHASE);
        return build(header, plan, firstInvoiceDate, card, account.id(), account.book(), interestCategory, chain, today);
    }

    public static Transaction toPerson(TransactionHeader header, CardCashOutPlan plan, LocalDate firstInvoiceDate, CreditCard card,
                                       UUID debtId, Category interestCategory, InvoiceChain chain, LocalDate today) {
        TransactionHeader.required(debtId, "debtId");
        return build(header, plan, firstInvoiceDate, card, null, BookRef.receivable(debtId), interestCategory, chain, today);
    }

    private static Transaction build(TransactionHeader header, CardCashOutPlan plan, LocalDate firstInvoiceDate, CreditCard card,
                                     UUID accountId, BookRef principalBook, Category interestCategory, InvoiceChain chain,
                                     LocalDate today) {
        AccountTransactions.requireHeaderAndAmount(header, plan.total());
        CardTransactions.requireUsableCard(header, plan.total(), card);
        CardTransactions.requireChain(card, chain, today);
        AccountTransactions.requireCategory(header, interestCategory, CategoryKind.EXPENSE);
        TransactionHeader.required(firstInvoiceDate, "firstInvoiceDate");
        List<TransactionInstallment> schedule = InstallmentPlan.generate(header.id(), plan.total(), plan.installments().size(),
                firstInvoiceDate, InvoiceAllocator.forInstallments(firstInvoiceDate, plan.installments().size(), chain, today));
        List<LedgerEntry> entries = new ArrayList<>();
        for (int i = 0; i < schedule.size(); i++) {
            TransactionInstallment installment = schedule.get(i);
            Money interest = plan.interestParts().get(i);
            if (interest.isNegative() || !installment.amount().equals(plan.installments().get(i))) {
                throw new CardCashOutInvalidException("as parcelas não fecham com o principal e os juros.");
            }
            List<Posting> postings = new ArrayList<>();
            postings.add(new Posting(card.book(), installment.amount().negate(), installment.effectiveDate()));
            if (interest.isPositive()) {
                postings.add(new Posting(interestCategory.book(), interest, installment.effectiveDate()));
            }
            postings.add(new Posting(BookRef.equity(), plan.principalParts().get(i), installment.effectiveDate()));
            if (i == 0) {
                postings.add(new Posting(principalBook, plan.principal(), header.date()));
                postings.add(new Posting(BookRef.equity(), plan.principal().negate(), header.date()));
            }
            entries.addAll(LedgerEntry.activeGroup(PostingSource.installment(header.id(), installment.id()), postings));
        }
        Transaction.Builder builder = Transaction.builder(header, TransactionType.CARD_PURCHASE, plan.total())
                .card(card.id()).category(interestCategory.id()).installments(schedule).entries(entries);
        if (accountId != null) {
            builder.account(accountId);
        }
        return builder.build();
    }
}
