package com.moneylog.infra.quickentry.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.moneylog.core.quickentry.domain.TokenRevocationReason;

public interface QuickEntryTokenRepositoryJpa extends JpaRepository<QuickEntryTokenEntity, UUID> {

    Optional<QuickEntryTokenEntity> findByUserIdAndId(UUID userId, UUID id);

    Optional<QuickEntryTokenEntity> findByUserIdAndTokenHash(UUID userId, String tokenHash);

    Optional<QuickEntryTokenEntity> findByTokenHash(String tokenHash);

    List<QuickEntryTokenEntity> findByUserIdOrderByCreatedAtDescIdAsc(UUID userId);

    @Query("select count(t) from QuickEntryTokenEntity t where t.userId = :userId and t.revokedAt is null and t.expiresAt > :now")
    long countActive(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QuickEntryTokenEntity t set t.revokedAt = :now, t.revokedReason = :reason, t.updatedAt = :now
             where t.userId = :userId and t.revokedAt is null and t.expiresAt > :now
            """)
    int revokeAllActive(@Param("userId") UUID userId, @Param("reason") TokenRevocationReason reason, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QuickEntryTokenEntity t set t.lastUsedAt = :at, t.lastUsedIp = :ip, t.lastUsedUserAgent = :userAgent, t.updatedAt = :at
             where t.userId = :userId and t.id = :id and t.revokedAt is null
               and (t.lastUsedAt is null or t.lastUsedAt <= :usedBefore)
            """)
    int recordUse(@Param("userId") UUID userId, @Param("id") UUID id, @Param("at") Instant at, @Param("usedBefore") Instant usedBefore,
                  @Param("ip") String ip, @Param("userAgent") String userAgent);
}
