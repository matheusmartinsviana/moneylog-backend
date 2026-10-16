package com.moneylog.core.transaction.app.gateways;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.transaction.domain.ForeignCharge;

public interface ForeignChargeGateway {

    void save(UUID userId, UUID transactionId, ForeignCharge charge);

    Map<UUID, ForeignCharge> findByTransactions(UUID userId, Collection<UUID> transactionIds);

    Map<UUID, ForeignCharge> findAll(UUID userId);
}
