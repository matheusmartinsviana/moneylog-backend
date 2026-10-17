package com.moneylog.core.asset.app.usecase;

import java.util.UUID;

import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

public interface ListAssetsUseCase {

    Slice<Versioned<Asset>> handle(ListAssetsQuery query);

    record ListAssetsQuery(UUID userId, boolean archived, PageQuery page) {
    }
}
