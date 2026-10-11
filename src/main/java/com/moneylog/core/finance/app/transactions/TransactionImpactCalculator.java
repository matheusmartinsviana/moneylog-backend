package com.moneylog.core.finance.app.transactions;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.finance.app.transactions.TransactionDetails.ConsumptionImpact;
import com.moneylog.core.finance.app.transactions.TransactionDetails.Impact;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.Transaction;

public final class TransactionImpactCalculator {

    private TransactionImpactCalculator() {
    }

    public static Impact of(Transaction transaction, Map<UUID, Account> accountsById) {
        return of(transaction, accountsById, null, Money.zero(CurrencyCode.BRL));
    }

    public static Impact of(Transaction transaction, Map<UUID, Account> accountsById, LocalDate today, Money commitment) {
        Money zero = Money.zero(CurrencyCode.BRL);
        Money cash = zero;
        Money netWorth = zero;
        Map<Competence, Money> consumption = new TreeMap<>();
        for (Posting posting : transaction.postings()) {
            if (posting.bookType() == BookType.ACCOUNT) {
                Account account = accountsById.get(posting.book().id());
                if (account != null && account.liquidity() == LiquidityClass.CASH) {
                    cash = cash.add(posting.amount());
                }
            }
            boolean futureCard = today != null && posting.bookType() == BookType.CARD && posting.effectiveDate().isAfter(today);
            if (posting.bookType().isNetWorth() && !futureCard) {
                netWorth = netWorth.add(posting.amount());
            }
            if (posting.bookType() == BookType.EXPENSE) {
                consumption.merge(posting.competence(), posting.amount(), Money::add);
            }
        }
        List<ConsumptionImpact> byCompetence = consumption.entrySet().stream()
                .map(entry -> new ConsumptionImpact(entry.getKey(), entry.getValue())).toList();
        return new Impact(cash, byCompetence, netWorth, commitment);
    }
}
