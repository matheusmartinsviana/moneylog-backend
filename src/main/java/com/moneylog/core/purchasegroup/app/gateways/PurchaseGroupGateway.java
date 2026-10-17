package com.moneylog.core.purchasegroup.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.purchasegroup.domain.PurchaseGroup;
import com.moneylog.core.sk.persistence.Versioned;

public interface PurchaseGroupGateway {

    Versioned<PurchaseGroup> insert(PurchaseGroup group);

    Versioned<PurchaseGroup> update(PurchaseGroup group, long expectedVersion);

    void lockForUpdate(UUID userId, UUID id);

    Optional<Versioned<PurchaseGroup>> findById(UUID userId, UUID id);

    List<Versioned<PurchaseGroup>> findAll(UUID userId);

    Optional<UUID> groupIdOfTransaction(UUID userId, UUID transactionId);
}
