package com.moneylog.core.asset.app.usecase;

import java.util.UUID;

import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.sk.persistence.Versioned;

public interface GetAssetUseCase {

    Versioned<Asset> handle(GetAssetQuery query);

    record GetAssetQuery(UUID userId, UUID assetId) {
    }
}
