package com.moneylog.infra.asset.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.asset.app.gateways.AssetGateway;
import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.asset.domain.BalancePoint;
import com.moneylog.core.asset.exception.AssetExceptions.AssetNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.asset.persistence.AssetBalanceEntity;
import com.moneylog.infra.asset.persistence.AssetBalanceRepositoryJpa;
import com.moneylog.infra.asset.persistence.AssetEntity;
import com.moneylog.infra.asset.persistence.AssetRepositoryJpa;
import com.moneylog.infra.persistence.OffsetLimit;

public class AssetGatewayImpl implements AssetGateway {

    private final AssetRepositoryJpa assets;
    private final AssetBalanceRepositoryJpa balances;
    private final IdGenerator ids;
    private final TimeProvider time;

    public AssetGatewayImpl(AssetRepositoryJpa assets, AssetBalanceRepositoryJpa balances, IdGenerator ids, TimeProvider time) {
        this.assets = assets;
        this.balances = balances;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public Versioned<Asset> insert(Asset asset) {
        AssetEntity entity = new AssetEntity(asset.id(), asset.userId(), asset.kind(), asset.name(), asset.employer(),
                asset.balance().cents(), asset.balanceDate(), asset.balance().currency().value(),
                asset.monthlyDeposit() == null ? null : asset.monthlyDeposit().cents(), asset.availability(), asset.countsAsReserve(),
                asset.isArchived(), time.now());
        entity.applyProvenance(asset.provenance());
        AssetEntity saved = assets.saveAndFlush(entity);
        balances.saveAllAndFlush(asset.history().stream().map(p -> AssetRepositoryMapper.pointRow(ids.next(), asset, p)).toList());
        return hydrate(saved);
    }

    @Override
    public Versioned<Asset> update(Asset asset, long expectedVersion) {
        return hydrate(persist(asset, expectedVersion));
    }

    @Override
    public Versioned<Asset> appendBalance(Asset asset, BalancePoint point, long expectedVersion) {
        AssetEntity saved = persist(asset, expectedVersion);
        balances.saveAndFlush(AssetRepositoryMapper.pointRow(ids.next(), asset, point));
        return hydrate(saved);
    }

    @Override
    public void lockForUpdate(UUID userId, UUID id) {
        assets.lockByIdAndUserId(id, userId);
    }

    @Override
    public Optional<Versioned<Asset>> findById(UUID userId, UUID id) {
        return assets.findByIdAndUserId(id, userId).map(this::hydrate);
    }

    @Override
    public Slice<Versioned<Asset>> list(UUID userId, boolean archived, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return new Slice<>(List.of(), false);
        }
        List<Versioned<Asset>> rows = assets.findPage(userId, archived, OffsetLimit.of(page)).stream()
                .map(e -> AssetRepositoryMapper.toVersioned(e, List.of())).toList();
        return Slice.fromOverfetch(rows, page);
    }

    @Override
    public List<Asset> findActive(UUID userId) {
        return assets.findByUserIdAndArchivedFalse(userId).stream().map(e -> AssetRepositoryMapper.toDomain(e, List.of())).toList();
    }

    @Override
    public List<Asset> findAllWithHistory(UUID userId) {
        java.util.Map<UUID, List<AssetBalanceEntity>> byAsset = new java.util.HashMap<>();
        for (AssetBalanceEntity row : balances.findByUserIdOrderByRecordedAtDescIdDesc(userId)) {
            byAsset.computeIfAbsent(row.getAssetId(), id -> new java.util.ArrayList<>()).add(row);
        }
        return assets.findByUserId(userId).stream()
                .map(e -> AssetRepositoryMapper.toDomain(e, byAsset.getOrDefault(e.getId(), List.of()))).toList();
    }

    private AssetEntity persist(Asset asset, long expectedVersion) {
        AssetEntity entity = assets.findByIdAndUserId(asset.id(), asset.userId()).orElseThrow(() -> new AssetNotFoundException(asset.id()));
        if (entity.getVersion() != expectedVersion) {
            throw new VersionConflictException(expectedVersion, entity.getVersion());
        }
        entity.apply(asset.kind(), asset.name(), asset.employer(), asset.balance().cents(), asset.balanceDate(),
                asset.monthlyDeposit() == null ? null : asset.monthlyDeposit().cents(), asset.availability(), asset.countsAsReserve(),
                asset.isArchived(), time.now());
        entity.applyProvenance(asset.provenance());
        return assets.saveAndFlush(entity);
    }

    private Versioned<Asset> hydrate(AssetEntity entity) {
        List<AssetBalanceEntity> history = balances.findByUserIdAndAssetIdOrderByRecordedAtDescIdDesc(entity.getUserId(), entity.getId());
        return AssetRepositoryMapper.toVersioned(entity, history);
    }
}
