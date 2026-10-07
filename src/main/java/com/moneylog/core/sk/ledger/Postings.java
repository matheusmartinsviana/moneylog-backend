package com.moneylog.core.sk.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;
import com.moneylog.core.sk.ledger.LedgerExceptions.UnbalancedPostingsException;
import com.moneylog.core.sk.money.Money;

/**
 * Regras sobre conjuntos de lançamentos. A invariante central do razão: os postings de cada movimentação somam zero.
 */
public final class Postings {

    private Postings() {
    }

    /**
     * Passa se Σ = 0. Lista vazia é balanceada. Moedas diferentes lançam {@code CurrencyMismatchException}.
     *
     * @throws UnbalancedPostingsException se a soma for diferente de zero
     */
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

    /** Devolve uma lista imutável depois de verificar que ela fecha em zero. */
    public static List<Posting> balanced(Posting... postings) {
        List<Posting> list = List.of(postings);
        assertBalanced(list);
        return list;
    }

    /** Soma de uma lista não vazia de lançamentos (todos na mesma moeda). */
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
