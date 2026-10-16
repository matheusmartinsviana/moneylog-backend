package com.moneylog.infra.income.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncomeDeductionRepositoryJpa extends JpaRepository<IncomeDeductionEntity, UUID> {

    List<IncomeDeductionEntity> findByUserIdAndProfileIdInOrderByProfileIdAscPositionAsc(UUID userId, Collection<UUID> profileIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from IncomeDeductionEntity d where d.userId = :userId and d.profileId = :profileId")
    void deleteAllOf(@Param("userId") UUID userId, @Param("profileId") UUID profileId);
}
