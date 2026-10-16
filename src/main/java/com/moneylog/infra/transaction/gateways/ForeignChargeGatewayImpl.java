package com.moneylog.infra.transaction.gateways;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.ForeignChargeGateway;
import com.moneylog.core.transaction.domain.ForeignCharge;
import com.moneylog.infra.transaction.persistence.ForeignChargeEntity;
import com.moneylog.infra.transaction.persistence.ForeignChargeRepositoryJpa;

public class ForeignChargeGatewayImpl implements ForeignChargeGateway {

    private final ForeignChargeRepositoryJpa repository;
    private final TimeProvider time;

    public ForeignChargeGatewayImpl(ForeignChargeRepositoryJpa repository, TimeProvider time) {
        this.repository = repository;
        this.time = time;
    }

    @Override
    public void save(UUID userId, UUID transactionId, ForeignCharge charge) {
        repository.saveAndFlush(new ForeignChargeEntity(transactionId, userId, charge.original().cents(),
                charge.original().currency().value(), charge.fxRate(), charge.iofBps(), time.now()));
    }

    @Override
    public Map<UUID, ForeignCharge> findAll(UUID userId) {
        Map<UUID, ForeignCharge> found = new HashMap<>();
        for (ForeignChargeEntity e : repository.findByUserId(userId)) {
            found.put(e.getTransactionId(), new ForeignCharge(
                    Money.ofCents(e.getOriginalAmountCents(), CurrencyCode.of(e.getOriginalCurrency())), e.getFxRate(), e.getIofBps()));
        }
        return found;
    }

    @Override
    public Map<UUID, ForeignCharge> findByTransactions(UUID userId, Collection<UUID> transactionIds) {
        Map<UUID, ForeignCharge> found = new HashMap<>();
        if (transactionIds.isEmpty()) {
            return found;
        }
        for (ForeignChargeEntity e : repository.findByUserIdAndTransactionIdIn(userId, transactionIds)) {
            found.put(e.getTransactionId(), new ForeignCharge(
                    Money.ofCents(e.getOriginalAmountCents(), CurrencyCode.of(e.getOriginalCurrency())), e.getFxRate(), e.getIofBps()));
        }
        return found;
    }
}
