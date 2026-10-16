package com.moneylog.core.asset.app.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;

public interface RecordAssetBalanceUseCase {

    Versioned<Asset> handle(RecordAssetBalanceCommand command);

    record RecordAssetBalanceCommand(UUID userId, UUID assetId, Money balance, LocalDate date) {
    }
}
