package com.moneylog.core.asset.app.usecase;

import com.moneylog.core.asset.app.AssetSpec;
import com.moneylog.core.asset.app.gateways.AssetGateway;
import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.asset.exception.AssetExceptions.AssetNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Versioned;

public class UpdateAssetUseCaseImpl implements UpdateAssetUseCase {

    private final AssetGateway assets;

    public UpdateAssetUseCaseImpl(AssetGateway assets) {
        this.assets = assets;
    }

    @Override
    public Versioned<Asset> handle(UpdateAssetCommand command) {
        assets.lockForUpdate(command.userId(), command.assetId());
        Versioned<Asset> current = assets.findById(command.userId(), command.assetId())
                .orElseThrow(() -> new AssetNotFoundException(command.assetId()));
        if (current.version() != command.version()) {
            throw new VersionConflictException(command.version(), current.version());
        }
        AssetSpec s = command.spec();
        Asset asset = current.value();
        asset.revise(s.kind(), s.name(), s.employer(), s.monthlyDeposit(), s.availability(), s.countsAsReserve());
        if (s.provenance() != null) {
            asset.defineProvenance(s.provenance());
        }
        return assets.update(asset, command.version());
    }
}
