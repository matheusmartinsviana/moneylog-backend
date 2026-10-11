package com.moneylog.core.sk.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;
import com.moneylog.core.sk.ledger.LedgerExceptions.UnbalancedPostingsException;
import com.moneylog.core.sk.money.Money;

public final class Postings {

    private Postings() {
    }

    public static void assertBalanced(Collection<Posting> postings) {
        Objects.requireNonNull(postings, "postings");
        if (postings.isEmpty()) {
            return;
        }
        Money sum = sum(postings);
        if (!sum.isZero()) {
            throw new UnbalancedPostingsException(sum);
        }
    }

    public static List<Posting> balanced(Posting... postings) {
        List<Posting> list = List.of(postings);
        assertBalanced(list);
        return list;
    }

    public static Money sum(Collection<Posting> postings) {
        if (postings.isEmpty()) {
            throw new InvalidPostingException("não há lançamentos para somar.");
        }
        Money sum = null;
        for (Posting posting : postings) {
            sum = sum == null ? posting.amount() : sum.add(posting.amount());
        }
        return sum;
    }
}
