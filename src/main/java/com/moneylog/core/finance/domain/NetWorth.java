package com.moneylog.core.finance.domain;

import java.time.LocalDate;

import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.money.Money;

/**
 * Lente de patrimônio (D1 §1.1, §2.8) na data: Σ {@code ACCOUNT} + {@code RECEIVABLE} + {@code CARD} + {@code PAYABLE}
 * com data ≤ a data consultada (passivos negativos). O passivo do cartão entra por competência da parcela (S-8);
 * {@code cardFutureInstallments} traz, informativo e <b>fora do total</b>, o valor das parcelas com data efetiva
 * futura. {@code cardLiabilities} pode ser positivo (crédito no cartão).
 */
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
