package com.moneylog.infra.finance.gateways;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.infra.account.gateways.AccountRepositoryMapper;
import com.moneylog.infra.card.gateways.CardRepositoryMapper;
import com.moneylog.infra.card.persistence.BillingRuleEntity;
import com.moneylog.infra.card.persistence.BillingRuleRepositoryJpa;
import com.moneylog.infra.card.persistence.CardEntity;
import com.moneylog.infra.card.persistence.CardRepositoryJpa;
import com.moneylog.infra.account.persistence.AccountRepositoryJpa;
import com.moneylog.infra.debt.gateways.DebtHydrator;
import com.moneylog.infra.debt.persistence.DebtRepositoryJpa;
import com.moneylog.infra.goal.gateways.GoalRepositoryMapper;
import com.moneylog.infra.goal.persistence.GoalEntity;
import com.moneylog.infra.goal.persistence.GoalRepositoryJpa;
import com.moneylog.infra.goal.persistence.GoalReservationEntity;
import com.moneylog.infra.goal.persistence.GoalReservationRepositoryJpa;
import com.moneylog.infra.ledger.gateways.LedgerRepositoryMapper;
import com.moneylog.infra.ledger.persistence.LedgerPostingEntity;
import com.moneylog.infra.ledger.persistence.LedgerPostingRepositoryJpa;
import com.moneylog.infra.transaction.gateways.TransactionHydrator;
import com.moneylog.infra.transaction.persistence.TransactionEntity;
import com.moneylog.infra.transaction.persistence.TransactionRepositoryJpa;

public class FinancialRecordsGatewayImpl implements FinancialRecordsGateway {

    private static final int READ_ATTEMPTS = 4;
    private static final Logger log = LoggerFactory.getLogger(FinancialRecordsGatewayImpl.class);

    private final AccountRepositoryJpa accounts;
    private final TransactionRepositoryJpa transactions;
    private final LedgerPostingRepositoryJpa postings;
    private final CardRepositoryJpa cards;
    private final BillingRuleRepositoryJpa billingRules;
    private final TransactionHydrator hydrator;
    private final DebtRepositoryJpa debts;
    private final DebtHydrator debtHydrator;
    private final GoalRepositoryJpa goals;
    private final GoalReservationRepositoryJpa goalReservations;
    private final EntityManager entityManager;

    @SuppressWarnings("java:S107")
    public FinancialRecordsGatewayImpl(AccountRepositoryJpa accounts, TransactionRepositoryJpa transactions,
                                       LedgerPostingRepositoryJpa postings, CardRepositoryJpa cards,
                                       BillingRuleRepositoryJpa billingRules, TransactionHydrator hydrator,
                                       DebtRepositoryJpa debts, DebtHydrator debtHydrator, GoalRepositoryJpa goals,
                                       GoalReservationRepositoryJpa goalReservations, EntityManager entityManager) {
        this.entityManager = entityManager;
        this.goals = goals;
        this.goalReservations = goalReservations;
        this.debts = debts;
        this.debtHydrator = debtHydrator;
        this.accounts = accounts;
        this.transactions = transactions;
        this.postings = postings;
        this.cards = cards;
        this.billingRules = billingRules;
        this.hydrator = hydrator;
    }

    @Override
    public FinancialRecords load(UUID userId) {
        boolean writeTransaction = TransactionSynchronizationManager.isActualTransactionActive()
                && !Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
        if (!writeTransaction) {
            return loadOnce(userId);
        }
        DomainException last = null;
        for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++) {
            try {
                return loadOnce(userId);
            } catch (DomainException e) {
                last = e;
                entityManager.flush();
                entityManager.clear();
                log.warn("Leitura inconsistente do razão (escrita concorrente), tentativa {}/{}: {}", attempt + 1, READ_ATTEMPTS, e.toString());
            }
        }
        throw new CannotAcquireLockException("leitura do razão colidiu com uma escrita concorrente", last);
    }

    private FinancialRecords loadOnce(UUID userId) {
        List<Account> loadedAccounts = accounts.findByUserId(userId).stream().map(AccountRepositoryMapper::toDomain).toList();
        Map<UUID, List<LedgerEntry>> entriesByTransaction = new HashMap<>();
        for (LedgerPostingEntity posting : postings.findAllOfTransactions(userId)) {
            entriesByTransaction.computeIfAbsent(posting.getTransactionId(), id -> new ArrayList<>())
                    .add(LedgerRepositoryMapper.toDomain(posting));
        }
        List<TransactionEntity> entities = transactions.findByUserId(userId);
        TransactionHydrator.Extras extras = hydrator.loadAll(userId);
        List<Transaction> loadedTransactions = new ArrayList<>();
        for (TransactionEntity entity : entities) {
            loadedTransactions.add(hydrator.toDomain(entity, entriesByTransaction.getOrDefault(entity.getId(), List.of()), extras));
        }
        return new FinancialRecords(loadedAccounts, loadCards(userId), loadedTransactions,
                debtHydrator.loadAll(userId, debts.findByUserId(userId)), loadGoals(userId));
    }

    private List<Goal> loadGoals(UUID userId) {
        List<GoalEntity> entities = goals.findByUserId(userId);
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<GoalReservationEntity>> byGoal = new HashMap<>();
        for (GoalReservationEntity reservation : goalReservations.findByUserId(userId)) {
            byGoal.computeIfAbsent(reservation.getGoalId(), id -> new ArrayList<>()).add(reservation);
        }
        return entities.stream().map(e -> GoalRepositoryMapper.toDomain(e, byGoal.getOrDefault(e.getId(), List.of()))).toList();
    }

    private List<CreditCard> loadCards(UUID userId) {
        Map<UUID, List<BillingRuleEntity>> rulesByCard = new HashMap<>();
        for (BillingRuleEntity rule : billingRules.findByUserId(userId)) {
            rulesByCard.computeIfAbsent(rule.getCardId(), id -> new ArrayList<>()).add(rule);
        }
        List<CreditCard> loaded = new ArrayList<>();
        for (CardEntity entity : cards.findByUserId(userId)) {
            loaded.add(CardRepositoryMapper.toDomain(entity, rulesByCard.getOrDefault(entity.getId(), List.of())));
        }
        return loaded;
    }
}
