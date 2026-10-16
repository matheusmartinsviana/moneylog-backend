package com.moneylog.core.asset.app.usecase;

import com.moneylog.core.asset.app.gateways.AssetGateway;
import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.asset.exception.AssetExceptions.AssetNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;

public class ArchiveAssetUseCaseImpl implements ArchiveAssetUseCase {

    private final AssetGateway assets;

    public ArchiveAssetUseCaseImpl(AssetGateway assets) {
        this.assets = assets;
    }

    @Override
    public Versioned<Asset> handle(ArchiveAssetCommand command) {
        assets.lockForUpdate(command.userId(), command.assetId());
        Versioned<Asset> current = assets.findById(command.userId(), command.assetId())
                .orElseThrow(() -> new AssetNotFoundException(command.assetId()));
        Asset asset = current.value();
        asset.archive();
        return assets.update(asset, current.version());
    }
}
