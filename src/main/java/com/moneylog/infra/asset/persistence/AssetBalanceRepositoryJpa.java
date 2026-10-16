package com.moneylog.infra.asset.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetBalanceRepositoryJpa extends JpaRepository<AssetBalanceEntity, UUID> {

    List<AssetBalanceEntity> findByUserIdAndAssetIdOrderByRecordedAtDescIdDesc(UUID userId, UUID assetId);

    List<AssetBalanceEntity> findByUserIdOrderByRecordedAtDescIdDesc(UUID userId);
}
