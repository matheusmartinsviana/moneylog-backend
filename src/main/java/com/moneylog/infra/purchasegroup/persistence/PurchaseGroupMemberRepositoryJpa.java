package com.moneylog.infra.purchasegroup.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseGroupMemberRepositoryJpa extends JpaRepository<PurchaseGroupMemberEntity, PurchaseGroupMemberId> {

    List<PurchaseGroupMemberEntity> findByUserIdAndGroupIdInOrderByGroupIdAscPositionAsc(UUID userId, Collection<UUID> groupIds);

    Optional<PurchaseGroupMemberEntity> findByUserIdAndTransactionId(UUID userId, UUID transactionId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PurchaseGroupMemberEntity m where m.userId = :userId and m.groupId = :groupId")
    void deleteAllOf(@Param("userId") UUID userId, @Param("groupId") UUID groupId);
}
