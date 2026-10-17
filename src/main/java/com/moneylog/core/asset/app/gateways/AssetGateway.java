package com.moneylog.core.asset.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.asset.domain.BalancePoint;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

public interface AssetGateway {

    Versioned<Asset> insert(Asset asset);

    Versioned<Asset> update(Asset asset, long expectedVersion);

    Versioned<Asset> appendBalance(Asset asset, BalancePoint point, long expectedVersion);

    void lockForUpdate(UUID userId, UUID id);

    Optional<Versioned<Asset>> findById(UUID userId, UUID id);

    Slice<Versioned<Asset>> list(UUID userId, boolean archived, PageQuery page);

    List<Asset> findActive(UUID userId);

    List<Asset> findAllWithHistory(UUID userId);
}
