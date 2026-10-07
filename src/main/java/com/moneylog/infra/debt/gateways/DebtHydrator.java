package com.moneylog.infra.debt.gateways;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.infra.debt.persistence.DebtEntity;
import com.moneylog.infra.ledger.gateways.LedgerRepositoryMapper;
import com.moneylog.infra.ledger.persistence.LedgerPostingEntity;
import com.moneylog.infra.ledger.persistence.LedgerPostingRepositoryJpa;
import com.moneylog.infra.transaction.persistence.TransactionRepositoryJpa;
import com.moneylog.infra.transaction.persistence.TransactionRepositoryJpa.DebtLinkRow;

/**
 * Reidrata dívidas em lote (sem consulta por item): as movimentações ligadas a elas (origem e quitações) e os lançamentos
 * próprios (reconhecimento e perdão). {@link #loadAll} é o carregamento único dos registros financeiros (S-79).
 */
public class DebtHydrator {

    private static final Set<PostingSourceType> OWN_ENTRIES = Set.of(PostingSourceType.DEBT_RECOGNITION, PostingSourceType.DEBT_CANCELLATION);

    private final TransactionRepositoryJpa transactions;
    private final LedgerPostingRepositoryJpa postings;

    public DebtHydrator(TransactionRepositoryJpa transactions, LedgerPostingRepositoryJpa postings) {
        this.transactions = transactions;
        this.postings = postings;
    }

    public List<Debt> toDomain(UUID userId, List<DebtEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Collection<UUID> ids = entities.stream().map(DebtEntity::getId).toList();
        return assemble(entities, group(transactions.findDebtLinks(userId, ids)),
                entries(postings.findBySources(userId, OWN_ENTRIES, ids)));
    }

    /** Todas as dívidas do usuário, com o que as liga às movimentações e ao razão. */
    public List<Debt> loadAll(UUID userId, List<DebtEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        return assemble(entities, group(transactions.findAllDebtLinks(userId)),
                entries(postings.findAllOfSourceTypes(userId, OWN_ENTRIES)));
    }

    private static List<Debt> assemble(List<DebtEntity> entities, Map<UUID, List<DebtLinkRow>> links,
                                       Map<UUID, List<LedgerEntry>> entries) {
        List<Debt> debts = new ArrayList<>();
        for (DebtEntity entity : entities) {
            debts.add(DebtRepositoryMapper.toDomain(entity, links.getOrDefault(entity.getId(), List.of()),
                    entries.getOrDefault(entity.getId(), List.of())));
        }
        return debts;
    }

    private static Map<UUID, List<DebtLinkRow>> group(List<DebtLinkRow> rows) {
        Map<UUID, List<DebtLinkRow>> byDebt = new HashMap<>();
        for (DebtLinkRow row : rows) {
            byDebt.computeIfAbsent(row.getDebtId(), id -> new ArrayList<>()).add(row);
        }
        return byDebt;
    }

    private static Map<UUID, List<LedgerEntry>> entries(List<LedgerPostingEntity> rows) {
        Map<UUID, List<LedgerEntry>> byDebt = new HashMap<>();
        for (LedgerPostingEntity row : rows) {
            byDebt.computeIfAbsent(row.getSourceId(), id -> new ArrayList<>()).add(LedgerRepositoryMapper.toDomain(row));
        }
        return byDebt;
    }
}
