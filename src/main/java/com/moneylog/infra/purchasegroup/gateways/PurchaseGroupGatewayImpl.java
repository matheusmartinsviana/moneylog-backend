package com.moneylog.infra.purchasegroup.gateways;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.purchasegroup.app.gateways.PurchaseGroupGateway;
import com.moneylog.core.purchasegroup.domain.PurchaseGroup;
import com.moneylog.core.purchasegroup.exception.PurchaseGroupExceptions.PurchaseGroupNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.purchasegroup.persistence.PurchaseGroupEntity;
import com.moneylog.infra.purchasegroup.persistence.PurchaseGroupMemberEntity;
import com.moneylog.infra.purchasegroup.persistence.PurchaseGroupMemberRepositoryJpa;
import com.moneylog.infra.purchasegroup.persistence.PurchaseGroupRepositoryJpa;

public class PurchaseGroupGatewayImpl implements PurchaseGroupGateway {

    private final PurchaseGroupRepositoryJpa groups;
    private final PurchaseGroupMemberRepositoryJpa members;
    private final TimeProvider time;

    public PurchaseGroupGatewayImpl(PurchaseGroupRepositoryJpa groups, PurchaseGroupMemberRepositoryJpa members, TimeProvider time) {
        this.groups = groups;
        this.members = members;
        this.time = time;
    }

    @Override
    public Versioned<PurchaseGroup> insert(PurchaseGroup group) {
        PurchaseGroupEntity saved = groups.saveAndFlush(new PurchaseGroupEntity(group.id(), group.userId(), group.name(), group.interest(),
                group.declaredTotal() == null ? null : group.declaredTotal().cents(), time.now()));
        List<PurchaseGroupMemberEntity> rows = members.saveAllAndFlush(memberRows(group));
        return toVersioned(saved, rows);
    }

    @Override
    public Versioned<PurchaseGroup> update(PurchaseGroup group, long expectedVersion) {
        PurchaseGroupEntity entity = groups.findByIdAndUserId(group.id(), group.userId())
                .orElseThrow(() -> new PurchaseGroupNotFoundException(group.id()));
        if (entity.getVersion() != expectedVersion) {
            throw new VersionConflictException(expectedVersion, entity.getVersion());
        }
        entity.apply(group.name(), group.interest(), time.now());
        PurchaseGroupEntity saved = groups.saveAndFlush(entity);
        members.deleteAllOf(group.userId(), group.id());
        List<PurchaseGroupMemberEntity> rows = members.saveAllAndFlush(memberRows(group));
        return toVersioned(saved, rows);
    }

    @Override
    public void lockForUpdate(UUID userId, UUID id) {
        groups.lockByIdAndUserId(id, userId);
    }

    @Override
    public Optional<Versioned<PurchaseGroup>> findById(UUID userId, UUID id) {
        return groups.findByIdAndUserId(id, userId).map(e -> hydrate(userId, List.of(e)).get(0));
    }

    @Override
    public List<Versioned<PurchaseGroup>> findAll(UUID userId) {
        return hydrate(userId, groups.findAllOf(userId));
    }

    @Override
    public Optional<UUID> groupIdOfTransaction(UUID userId, UUID transactionId) {
        return members.findByUserIdAndTransactionId(userId, transactionId).map(PurchaseGroupMemberEntity::getGroupId);
    }

    private List<PurchaseGroupMemberEntity> memberRows(PurchaseGroup group) {
        List<PurchaseGroupMemberEntity> rows = new ArrayList<>();
        int position = 1;
        for (UUID transactionId : group.members()) {
            rows.add(new PurchaseGroupMemberEntity(group.id(), transactionId, group.userId(), position++, time.now()));
        }
        return rows;
    }

    private List<Versioned<PurchaseGroup>> hydrate(UUID userId, List<PurchaseGroupEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<PurchaseGroupMemberEntity>> byGroup = members
                .findByUserIdAndGroupIdInOrderByGroupIdAscPositionAsc(userId, entities.stream().map(PurchaseGroupEntity::getId).toList())
                .stream().collect(Collectors.groupingBy(PurchaseGroupMemberEntity::getGroupId));
        return entities.stream().map(e -> toVersioned(e, byGroup.getOrDefault(e.getId(), List.of()))).toList();
    }

    private static Versioned<PurchaseGroup> toVersioned(PurchaseGroupEntity e, List<PurchaseGroupMemberEntity> rows) {
        List<UUID> ids = rows.stream().sorted(java.util.Comparator.comparingInt(PurchaseGroupMemberEntity::getPosition))
                .map(PurchaseGroupMemberEntity::getTransactionId).toList();
        Money declared = e.getDeclaredTotalCents() == null ? null : Money.ofCents(e.getDeclaredTotalCents(), CurrencyCode.BRL);
        PurchaseGroup group = PurchaseGroup.restore(e.getId(), e.getUserId(), e.getName(), e.isInterest(), declared, ids);
        return new Versioned<>(group, e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
