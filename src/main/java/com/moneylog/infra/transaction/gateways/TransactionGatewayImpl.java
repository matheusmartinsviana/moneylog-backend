package com.moneylog.infra.transaction.gateways;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.query.TransactionFilter;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionState;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;
import com.moneylog.infra.card.gateways.InvoiceRowGateway;
import com.moneylog.infra.card.gateways.InvoiceRowGateway.InvoiceRow;
import com.moneylog.infra.ledger.gateways.LedgerRepositoryMapper;
import com.moneylog.infra.ledger.persistence.LedgerPostingRepositoryJpa;
import com.moneylog.infra.transaction.persistence.CardPurchaseShareEntity;
import com.moneylog.infra.transaction.persistence.CardPurchaseShareRepositoryJpa;
import com.moneylog.infra.transaction.persistence.InstallmentEntity;
import com.moneylog.infra.transaction.persistence.InstallmentRepositoryJpa;
import com.moneylog.infra.transaction.persistence.TransactionEntity;
import com.moneylog.infra.transaction.persistence.TransactionRepositoryJpa;

public class TransactionGatewayImpl implements TransactionGateway {

    private final TransactionRepositoryJpa repository;
    private final InstallmentRepositoryJpa installments;
    private final CardPurchaseShareRepositoryJpa shares;
    private final LedgerPostingRepositoryJpa postings;
    private final TransactionHydrator hydrator;
    private final InvoiceRowGateway invoices;
    private final CardGateway cards;
    private final TimeProvider time;
    private final EntityManager entityManager;

    @SuppressWarnings("java:S107")
    public TransactionGatewayImpl(TransactionRepositoryJpa repository, InstallmentRepositoryJpa installments,
                                  CardPurchaseShareRepositoryJpa shares, LedgerPostingRepositoryJpa postings, TransactionHydrator hydrator, InvoiceRowGateway invoices,
                                  CardGateway cards, TimeProvider time, EntityManager entityManager) {
        this.repository = repository;
        this.installments = installments;
        this.shares = shares;
        this.postings = postings;
        this.hydrator = hydrator;
        this.invoices = invoices;
        this.cards = cards;
        this.time = time;
        this.entityManager = entityManager;
    }

    private final class InvoiceBinding {
        private final UUID userId;
        private final UUID cardId;
        private final Map<LocalDate, InvoiceRow> byClosing = new HashMap<>();
        private CreditCard card;

        InvoiceBinding(UUID userId, UUID cardId) {
            this.userId = userId;
            this.cardId = cardId;
        }

        UUID idFor(LocalDate closingDate) {
            return byClosing.computeIfAbsent(closingDate, closing -> {
                if (card == null) {
                    card = cards.findById(userId, cardId).map(Persisted::value)
                            .orElseThrow(() -> new IllegalStateException("cartão " + cardId + " da movimentação não existe"));
                }
                return new InvoiceRow(invoices.findOrCreate(userId, card, closing), cardId, closing);
            }).id();
        }

        Map<UUID, InvoiceRow> rows() {
            Map<UUID, InvoiceRow> byId = new HashMap<>();
            byClosing.values().forEach(row -> byId.put(row.id(), row));
            return byId;
        }
    }

    @Override
    public Persisted<Transaction> insert(Transaction transaction) {
        TransactionState s = transaction.state();
        Instant now = time.now();
        InvoiceBinding binding = new InvoiceBinding(s.userId(), s.cardId());
        UUID invoiceId = s.invoiceClosingDate() == null ? null : binding.idFor(s.invoiceClosingDate());
        List<InstallmentEntity> rows = new ArrayList<>();
        for (TransactionInstallment i : s.installments()) {
            rows.add(new InstallmentEntity(i.id(), s.userId(), s.id(), i.number(), i.total(), i.amount().cents(),
                    i.amount().currency().value(), i.competence().toString(), i.effectiveDate(),
                    binding.idFor(i.invoiceClosingDate()), i.lateEntry(), i.canceledAt(), now));
        }
        TransactionEntity saved = repository.saveAndFlush(TransactionRepositoryMapper.toEntity(transaction, now, invoiceId));
        if (!rows.isEmpty()) {
            installments.saveAllAndFlush(rows);
        }
        List<CardPurchaseShareEntity> shareRows = TransactionRepositoryMapper.shareRows(transaction, now);
        if (!shareRows.isEmpty()) {
            shares.saveAllAndFlush(shareRows);
        }
        return new Persisted<>(TransactionRepositoryMapper.toDomain(saved, transaction.entries(), rows, binding.rows(), shareRows),
                saved.getCreatedAt(), saved.getUpdatedAt());
    }

    @Override
    public Persisted<Transaction> update(Transaction transaction) {
        TransactionEntity entity = repository.findByIdAndUserId(transaction.id(), transaction.userId())
                .orElseThrow(() -> new TransactionNotFoundException(transaction.id()));
        if (entity.getVersion() != transaction.version()) {
            throw new VersionConflictException(transaction.version(), entity.getVersion());
        }
        TransactionState s = transaction.state();
        Instant now = time.now();
        InvoiceBinding binding = new InvoiceBinding(s.userId(), s.cardId());
        UUID invoiceId = s.invoiceClosingDate() == null ? null : binding.idFor(s.invoiceClosingDate());
        String direction = s.investmentDirection() != null ? s.investmentDirection().name()
                : s.adjustmentDirection() == null ? null : s.adjustmentDirection().name();
        entity.apply(s.status(), s.amount().cents(), s.date(), s.description(), s.notes(), s.paymentMethod(), s.accountId(),
                s.counterpartAccountId(), invoiceId, s.lateEntry(), s.categoryId(), s.refundOfId(), direction,
                s.adjustmentKind() == null ? null : s.adjustmentKind().name(), s.reason(), s.voidReason(), s.voidedAt(),
                s.incomeCompetence() == null ? null : s.incomeCompetence().toString(), s.nature(), now);
        TransactionEntity saved = repository.saveAndFlush(entity);
        List<InstallmentEntity> rows = syncInstallments(s, binding, now);
        Map<UUID, InvoiceRow> invoiceRows = new HashMap<>(binding.rows());
        if (!rows.isEmpty()) {
            invoiceRows.putAll(invoices.rowsByIds(s.userId(), rows.stream().map(InstallmentEntity::getInvoiceId).distinct().toList()));
        }
        List<CardPurchaseShareEntity> shareRows = s.type() == com.moneylog.core.transaction.domain.TransactionType.CARD_PURCHASE
                ? syncShares(transaction, now) : List.of();
        return new Persisted<>(TransactionRepositoryMapper.toDomain(saved, transaction.entries(), rows, invoiceRows, shareRows),
                saved.getCreatedAt(), saved.getUpdatedAt());
    }

    private List<CardPurchaseShareEntity> syncShares(Transaction transaction, Instant now) {
        List<CardPurchaseShareEntity> existing = shares.findByUserIdAndTransactionIdIn(transaction.userId(), List.of(transaction.id()))
                .stream().sorted(java.util.Comparator.comparingInt(CardPurchaseShareEntity::getPosition)).toList();
        List<CardPurchaseShareEntity> desired = TransactionRepositoryMapper.shareRows(transaction, now);
        if (sameShares(existing, desired)) {
            return existing;
        }
        shares.deleteByTransaction(transaction.userId(), transaction.id());
        return desired.isEmpty() ? List.of() : shares.saveAllAndFlush(desired);
    }

    private static boolean sameShares(List<CardPurchaseShareEntity> existing, List<CardPurchaseShareEntity> desired) {
        if (existing.size() != desired.size()) {
            return false;
        }
        for (int i = 0; i < existing.size(); i++) {
            CardPurchaseShareEntity a = existing.get(i);
            CardPurchaseShareEntity b = desired.get(i);
            if (a.getSplitMode() != b.getSplitMode() || !java.util.Objects.equals(a.getPersonId(), b.getPersonId())
                    || !java.util.Objects.equals(a.getBps(), b.getBps()) || !java.util.Objects.equals(a.getAmountCents(), b.getAmountCents())) {
                return false;
            }
        }
        return true;
    }

    private List<InstallmentEntity> syncInstallments(TransactionState s, InvoiceBinding binding, Instant now) {
        if (s.installments().isEmpty()) {
            return List.of();
        }
        List<InstallmentEntity> rows = installments.findByUserIdAndTransactionIdIn(s.userId(), List.of(s.id()));
        Map<UUID, InstallmentEntity> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.getId(), row));
        Map<UUID, InvoiceRow> current = invoices.rowsByIds(s.userId(), rows.stream().map(InstallmentEntity::getInvoiceId).distinct().toList());
        for (TransactionInstallment i : s.installments()) {
            InstallmentEntity row = byId.get(i.id());
            if (row == null) {
                throw new IllegalStateException("a parcela " + i.id() + " não está gravada");
            }
            InvoiceRow currentRow = current.get(row.getInvoiceId());
            UUID invoiceId = currentRow != null && currentRow.closingDate().equals(i.invoiceClosingDate()) ? row.getInvoiceId()
                    : binding.idFor(i.invoiceClosingDate());
            row.apply(invoiceId, i.canceledAt(), now);
        }
        return installments.saveAllAndFlush(rows);
    }

    @Override
    public void lockForUpdate(UUID userId, UUID id) {
        repository.lockByIdAndUserId(id, userId);
    }

    @Override
    public void lockAllOfCard(UUID userId, UUID cardId) {
        repository.lockIdsOfCard(userId, cardId);
    }

    @Override
    public Optional<Persisted<Transaction>> findById(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).map(entity -> {
            List<LedgerEntry> entries = postings.findByTransactions(userId, List.of(id)).stream()
                    .map(LedgerRepositoryMapper::toDomain).toList();
            return new Persisted<>(hydrator.toDomain(entity, entries, hydrator.loadFor(userId, List.of(entity))),
                    entity.getCreatedAt(), entity.getUpdatedAt());
        });
    }

    @Override
    public Slice<Persisted<Transaction>> list(UUID userId, TransactionFilter filter, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return new Slice<>(List.of(), false);
        }
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TransactionEntity> query = cb.createQuery(TransactionEntity.class);
        Root<TransactionEntity> root = query.from(TransactionEntity.class);
        query.select(root).where(predicates(cb, root, userId, filter).toArray(Predicate[]::new))
                .orderBy(cb.desc(root.get("transactionDate")), cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
        List<TransactionEntity> fetched = entityManager.createQuery(query).setFirstResult(Math.toIntExact(page.offset()))
                .setMaxResults(page.fetchSize()).getResultList();
        List<TransactionEntity> pageEntities = fetched.size() > page.pageSize() ? fetched.subList(0, page.pageSize()) : fetched;

        Map<UUID, List<LedgerEntry>> entriesById = new HashMap<>();
        if (!pageEntities.isEmpty()) {
            postings.findByTransactions(userId, pageEntities.stream().map(TransactionEntity::getId).toList())
                    .forEach(posting -> entriesById.computeIfAbsent(posting.getTransactionId(), id -> new ArrayList<>())
                            .add(LedgerRepositoryMapper.toDomain(posting)));
        }
        TransactionHydrator.Extras extras = hydrator.loadFor(userId, pageEntities);
        List<Persisted<Transaction>> items = pageEntities.stream()
                .map(entity -> new Persisted<>(hydrator.toDomain(entity, entriesById.getOrDefault(entity.getId(), List.of()), extras),
                        entity.getCreatedAt(), entity.getUpdatedAt()))
                .toList();
        return new Slice<>(items, fetched.size() > page.pageSize());
    }

    @Override
    public List<Transaction> findAllOfCard(UUID userId, UUID cardId) {
        return hydrate(userId, repository.findByUserIdAndCardId(userId, cardId));
    }

    @Override
    public List<Transaction> findActiveRefundsOf(UUID userId, UUID originalId) {
        return hydrate(userId, repository.findByUserIdAndRefundOfIdAndStatus(userId, originalId,
                com.moneylog.core.transaction.domain.TransactionStatus.ACTIVE));
    }

    private List<Transaction> hydrate(UUID userId, List<TransactionEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<LedgerEntry>> entriesById = new HashMap<>();
        postings.findByTransactions(userId, entities.stream().map(TransactionEntity::getId).toList())
                .forEach(posting -> entriesById.computeIfAbsent(posting.getTransactionId(), id -> new ArrayList<>())
                        .add(LedgerRepositoryMapper.toDomain(posting)));
        TransactionHydrator.Extras extras = hydrator.loadFor(userId, entities);
        return entities.stream().map(entity -> hydrator.toDomain(entity, entriesById.getOrDefault(entity.getId(), List.of()), extras))
                .toList();
    }

    private static List<Predicate> predicates(CriteriaBuilder cb, Root<TransactionEntity> root, UUID userId, TransactionFilter f) {
        List<Predicate> where = new ArrayList<>();
        where.add(cb.equal(root.get("userId"), userId));
        where.add(cb.equal(root.get("status"), f.status()));
        if (f.from() != null) {
            where.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("transactionDate"), f.from()));
        }
        if (f.to() != null) {
            where.add(cb.lessThanOrEqualTo(root.<LocalDate>get("transactionDate"), f.to()));
        }
        if (f.type() != null) {
            where.add(cb.equal(root.get("type"), f.type()));
        }
        if (f.accountId() != null) {
            where.add(cb.or(cb.equal(root.get("accountId"), f.accountId()), cb.equal(root.get("counterpartAccountId"), f.accountId())));
        }
        if (f.categoryId() != null) {
            where.add(cb.equal(root.get("categoryId"), f.categoryId()));
        }
        if (f.method() != null) {
            where.add(cb.equal(root.get("paymentMethod"), f.method()));
        }
        if (f.q() != null) {
            String escaped = f.q().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            where.add(cb.like(cb.lower(root.<String>get("description")), "%" + escaped + "%", '\\'));
        }
        return where;
    }

    @Override
    public Money sumActiveRefundsOf(UUID userId, UUID originalId) {
        return Money.ofCents(repository.sumActiveRefundsCents(userId, originalId), CurrencyCode.BRL);
    }
}
