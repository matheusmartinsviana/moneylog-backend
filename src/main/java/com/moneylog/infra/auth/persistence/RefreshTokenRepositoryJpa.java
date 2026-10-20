package com.moneylog.infra.auth.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RefreshTokenRepositoryJpa extends JpaRepository<RefreshTokenEntity, UUID> {

    @Query("select t.userId from RefreshTokenEntity t where t.tokenHash = :hash")
    Optional<UUID> findUserIdByHash(@Param("hash") String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshTokenEntity t where t.tokenHash = :hash")
    Optional<RefreshTokenEntity> findByHashForUpdate(@Param("hash") String hash);

    @Modifying
    @Query("update RefreshTokenEntity t set t.replacedBy = :replacedBy, t.replacedAt = :at, t.updatedAt = :at where t.id = :id")
    int markReplaced(@Param("id") UUID id, @Param("replacedBy") UUID replacedBy, @Param("at") Instant at);

    @Modifying
    @Query("delete from RefreshTokenEntity t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("update RefreshTokenEntity t set t.revokedAt = :at, t.updatedAt = :at where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("at") Instant at);

    @Modifying
    @Query("update RefreshTokenEntity t set t.revokedAt = :at, t.updatedAt = :at where t.userId = :userId and t.revokedAt is null")
    int revokeAllByUser(@Param("userId") UUID userId, @Param("at") Instant at);
}
