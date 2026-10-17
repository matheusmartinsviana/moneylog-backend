package com.moneylog.core.asset.app.usecase;

import java.util.UUID;

import com.moneylog.core.asset.app.AssetSpec;
import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.sk.persistence.Versioned;

public interface CreateAssetUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/assets";

    Versioned<Asset> handle(CreateAssetCommand command);

    record CreateAssetCommand(UUID userId, AssetSpec spec, UUID idempotencyKey) {
    }
}
