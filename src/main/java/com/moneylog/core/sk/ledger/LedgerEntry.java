package com.moneylog.core.sk.ledger;

import java.util.Collection;
import java.util.List;

import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;

public record LedgerEntry(PostingSource source, Posting posting, boolean active) {

    public LedgerEntry {
        if (source == null || posting == null) {
            throw new InvalidPostingException("origem e posting são obrigatórios.");
        }
    }

    public static List<LedgerEntry> activeGroup(PostingSource source, Collection<Posting> postings) {
        Postings.assertBalanced(postings);
        return postings.stream().map(p -> new LedgerEntry(source, p, true)).toList();
    }

    public LedgerEntry deactivate() {
        return active ? new LedgerEntry(source, posting, false) : this;
    }
}
