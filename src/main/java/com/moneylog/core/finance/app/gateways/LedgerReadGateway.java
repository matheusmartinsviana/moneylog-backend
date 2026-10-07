package com.moneylog.core.finance.app.gateways;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSource;

/**
 * Leitura do razão (S-79): os lançamentos de um usuário, para reidratar os agregados e montar o {@code Ledger} em
 * memória. Sempre filtrado por {@code userId}.
 */
public interface LedgerReadGateway {

    /** Lançamentos <b>ativos</b> do usuário agrupados por origem (a ordem dos grupos e dos lançamentos é estável). */
    Map<PostingSource, List<LedgerEntry>> activeEntries(UUID userId);

    /**
     * Todos os lançamentos (ativos e inativos) das movimentações informadas, agrupados pelo id da movimentação. Uma
     * movimentação estornada só tem lançamentos inativos e precisa deles para ser reidratada.
     */
    Map<UUID, List<LedgerEntry>> entriesOfTransactions(UUID userId, Collection<UUID> transactionIds);
}
