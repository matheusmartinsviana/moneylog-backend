package com.moneylog.core.asset.app.usecase;

import java.util.UUID;

import com.moneylog.core.asset.app.AssetSpec;
import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.sk.persistence.Versioned;

public interface UpdateAssetUseCase {

    Versioned<Asset> handle(UpdateAssetCommand command);

    record UpdateAssetCommand(UUID userId, UUID assetId, AssetSpec spec, long version) {
    }
}
