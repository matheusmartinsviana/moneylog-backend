package com.moneylog.core.asset.app.usecase;

import java.util.UUID;

import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.sk.persistence.Versioned;

public interface ArchiveAssetUseCase {

    Versioned<Asset> handle(ArchiveAssetCommand command);

    record ArchiveAssetCommand(UUID userId, UUID assetId) {
    }
}
