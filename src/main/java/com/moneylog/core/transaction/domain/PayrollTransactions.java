package com.moneylog.core.transaction.domain;

import java.util.ArrayList;
import java.util.List;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public final class PayrollTransactions {

    private PayrollTransactions() {
    }

    public static Transaction incomeWithLoan(TransactionHeader header, Money received, Account account, Category category,
                                             PaymentMethod method, Competence incomeCompetence, PayrollLoanShare loan) {
        AccountTransactions.requireHeaderAndAmount(header, received);
        AccountTransactions.requireCashAccount(header, received, account, TransactionType.INCOME);
        AccountTransactions.requireCategory(header, category, CategoryKind.INCOME);
        AccountTransactions.requireMethod(method, TransactionType.INCOME);
        Competence competence = AccountTransactions.validIncomeCompetence(incomeCompetence);
        Debt debt = loan.debt();
        debt.ensureBelongsTo(header.userId());
        debt.ensureDirection(DebtDirection.PAYABLE);
        Money principal = loan.principal();
        debt.ensureCanSettle(principal);
        if (loan.interest() != null && loan.interest().isPositive()) {
            AccountTransactions.requireCategory(header, loan.interestCategory(), CategoryKind.EXPENSE);
        }
        Money grossIncome = received.add(loan.applied());
        List<Posting> postings = new ArrayList<>();
        postings.add(new Posting(account.book(), received, header.date()));
        postings.add(new Posting(debt.book(), principal, header.date()));
        if (loan.interestOrZero().isPositive()) {
            postings.add(new Posting(loan.interestCategory().book(), loan.interest(), header.date()));
        }
        postings.add(new Posting(BookRef.income(category.id()), grossIncome.negate(), header.date(), competence));
        Transaction transaction = Transaction.builder(header, TransactionType.INCOME, received)
                .paymentMethod(method).account(account.id()).category(category.id()).incomeCompetence(competence)
                .debt(debt.id())
                .entries(AccountTransactions.entries(header, postings.toArray(Posting[]::new)))
                .build();
        debt.registerSettlement(transaction.id(), principal);
        return transaction;
    }
}
