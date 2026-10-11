package com.moneylog.core.finance.domain;

import java.time.LocalDate;

import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.money.Money;

public record NetWorth(LocalDate asOf, Money accounts, Money receivables, Money cardLiabilities, Money payables, Money total,
                       Money cardFutureInstallments) {

    public static NetWorth at(Ledger ledger, LocalDate asOf) {
        Money accounts = ledger.balance(BookType.ACCOUNT, asOf);
        Money receivables = ledger.balance(BookType.RECEIVABLE, asOf);
        Money card = ledger.balance(BookType.CARD, asOf);
        Money payables = ledger.balance(BookType.PAYABLE, asOf);
        Money future = ledger.sum(e -> e.posting().bookType() == BookType.CARD && e.source().installmentId() != null
                && e.posting().effectiveDate().isAfter(asOf)).negate();
        return new NetWorth(asOf, accounts, receivables, card, payables, accounts.add(receivables).add(card).add(payables), future);
    }
}
