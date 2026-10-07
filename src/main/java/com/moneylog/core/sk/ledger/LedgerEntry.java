package com.moneylog.core.sk.ledger;

import java.util.Collection;
import java.util.List;

import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;

/**
 * O que o gateway persiste em {@code ledger_postings} (D1 §1.1): origem + posting + vigência. Imutável: desativar
 * devolve uma cópia inativa. Posting nunca é reativado (correção = desativar o grupo e gravar um novo).
 */
public record LedgerEntry(PostingSource source, Posting posting, boolean active) {

    public LedgerEntry {
        if (source == null || posting == null) {
            throw new InvalidPostingException("origem e posting são obrigatórios.");
        }
    }

    /** Entradas ativas de um grupo, depois de verificar que ele fecha em zero. */
    public static List<LedgerEntry> activeGroup(PostingSource source, Collection<Posting> postings) {
        Postings.assertBalanced(postings);
        return postings.stream().map(p -> new LedgerEntry(source, p, true)).toList();
    }

    public LedgerEntry deactivate() {
        return active ? new LedgerEntry(source, posting, false) : this;
    }
}
