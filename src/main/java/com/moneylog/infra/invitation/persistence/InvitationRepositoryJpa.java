package com.moneylog.infra.invitation.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.moneylog.core.invitation.domain.InvitationStatus;

public interface InvitationRepositoryJpa extends JpaRepository<InvitationEntity, UUID> {

    Optional<InvitationEntity> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InvitationEntity i where i.id = :id")
    Optional<InvitationEntity> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InvitationEntity i where i.tokenHash = :hash")
    Optional<InvitationEntity> findByTokenHashForUpdate(@Param("hash") String hash);

    @Query("select i from InvitationEntity i where lower(i.email) = :email and i.status = :status")
    Optional<InvitationEntity> findByEmailAndStatus(@Param("email") String email, @Param("status") InvitationStatus status);

    List<InvitationEntity> findAllByOrderByCreatedAtDescIdAsc();

    @Query("select count(i) from InvitationEntity i where i.status = com.moneylog.core.invitation.domain.InvitationStatus.PENDING and i.expiresAt > :now")
    long countPendingNotExpired(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InvitationEntity i set i.status = com.moneylog.core.invitation.domain.InvitationStatus.EXPIRED, i.updatedAt = :now
             where lower(i.email) = :email and i.status = com.moneylog.core.invitation.domain.InvitationStatus.PENDING and i.expiresAt <= :now
            """)
    int expireOverdueOf(@Param("email") String email, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InvitationEntity i set i.status = com.moneylog.core.invitation.domain.InvitationStatus.EXPIRED, i.updatedAt = :now
             where i.status = com.moneylog.core.invitation.domain.InvitationStatus.PENDING and i.expiresAt <= :now
            """)
    int expireOverdue(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InvitationEntity i set i.status = com.moneylog.core.invitation.domain.InvitationStatus.ACCEPTED,
                   i.acceptedAt = :now, i.acceptedUserId = :userId, i.updatedAt = :now
             where i.id = :id and i.status = com.moneylog.core.invitation.domain.InvitationStatus.PENDING and i.expiresAt > :now
            """)
    int markAccepted(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InvitationEntity i set i.status = com.moneylog.core.invitation.domain.InvitationStatus.REVOKED, i.updatedAt = :now
             where i.id = :id and i.status = com.moneylog.core.invitation.domain.InvitationStatus.PENDING
            """)
    int revokeIfPending(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from InvitationEntity i
             where (i.status = com.moneylog.core.invitation.domain.InvitationStatus.EXPIRED and i.expiresAt < :cutoff)
                or (i.status = com.moneylog.core.invitation.domain.InvitationStatus.ACCEPTED and i.acceptedAt < :cutoff)
                or (i.status = com.moneylog.core.invitation.domain.InvitationStatus.REVOKED and i.updatedAt < :cutoff)
            """)
    int deleteFinalBefore(@Param("cutoff") Instant cutoff);
}
