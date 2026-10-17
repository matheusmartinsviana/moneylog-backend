package com.moneylog.core.asset.app.usecase;

import com.moneylog.core.asset.app.gateways.AssetGateway;
import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

public class ListAssetsUseCaseImpl implements ListAssetsUseCase {

    private final AssetGateway assets;

    public ListAssetsUseCaseImpl(AssetGateway assets) {
        this.assets = assets;
    }

    @Override
    public Slice<Versioned<Asset>> handle(ListAssetsQuery query) {
        return assets.list(query.userId(), query.archived(), query.page());
    }
}
