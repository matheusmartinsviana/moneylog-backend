package com.moneylog.infra.asset.gateways;

import java.util.List;

import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.asset.domain.BalancePoint;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.infra.asset.persistence.AssetBalanceEntity;
import com.moneylog.infra.asset.persistence.AssetEntity;

public final class AssetRepositoryMapper {

    private AssetRepositoryMapper() {
    }

    public static Asset toDomain(AssetEntity e, List<AssetBalanceEntity> history) {
        CurrencyCode currency = CurrencyCode.of(e.getCurrency().strip());
        List<BalancePoint> points = history.stream()
                .map(h -> new BalancePoint(Money.ofCents(h.getBalanceCents(), currency), h.getBalanceDate(), h.getRecordedAt())).toList();
        Asset asset = Asset.restore(e.getId(), e.getUserId(), e.getKind(), e.getName(), e.getEmployer(),
                Money.ofCents(e.getBalanceCents(), currency), e.getBalanceDate(),
                e.getMonthlyDepositCents() == null ? null : Money.ofCents(e.getMonthlyDepositCents(), currency), e.getAvailability(),
                e.isCountsAsReserve(), e.isArchived(), points);
        asset.defineProvenance(e.provenance());
        return asset;
    }

    public static Versioned<Asset> toVersioned(AssetEntity e, List<AssetBalanceEntity> history) {
        return new Versioned<>(toDomain(e, history), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    public static AssetBalanceEntity pointRow(java.util.UUID id, Asset asset, BalancePoint point) {
        return new AssetBalanceEntity(id, asset.userId(), asset.id(), point.balance().cents(), point.balance().currency().value(),
                point.date(), point.recordedAt());
    }
}
