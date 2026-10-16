package com.moneylog.core.asset.app.usecase;

import com.moneylog.core.asset.app.gateways.AssetGateway;
import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.asset.exception.AssetExceptions.AssetNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;

public class GetAssetUseCaseImpl implements GetAssetUseCase {

    private final AssetGateway assets;

    public GetAssetUseCaseImpl(AssetGateway assets) {
        this.assets = assets;
    }

    @Override
    public Versioned<Asset> handle(GetAssetQuery query) {
        return assets.findById(query.userId(), query.assetId()).orElseThrow(() -> new AssetNotFoundException(query.assetId()));
    }
}
