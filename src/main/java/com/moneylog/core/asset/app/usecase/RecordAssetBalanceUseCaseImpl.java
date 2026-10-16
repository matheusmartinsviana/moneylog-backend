package com.moneylog.core.asset.app.usecase;

import com.moneylog.core.asset.app.gateways.AssetGateway;
import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.asset.domain.BalancePoint;
import com.moneylog.core.asset.exception.AssetExceptions.AssetNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class RecordAssetBalanceUseCaseImpl implements RecordAssetBalanceUseCase {

    private final AssetGateway assets;
    private final TimeProvider time;

    public RecordAssetBalanceUseCaseImpl(AssetGateway assets, TimeProvider time) {
        this.assets = assets;
        this.time = time;
    }

    @Override
    public Versioned<Asset> handle(RecordAssetBalanceCommand command) {
        assets.lockForUpdate(command.userId(), command.assetId());
        Versioned<Asset> current = assets.findById(command.userId(), command.assetId())
                .orElseThrow(() -> new AssetNotFoundException(command.assetId()));
        Asset asset = current.value();
        BalancePoint point = asset.recordBalance(command.balance(), command.date(), time.now());
        return assets.appendBalance(asset, point, current.version());
    }
}
