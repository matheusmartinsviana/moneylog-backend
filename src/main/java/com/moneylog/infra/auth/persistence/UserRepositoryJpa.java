package com.moneylog.infra.auth.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import jakarta.persistence.LockModeType;

import com.moneylog.core.auth.domain.UserRole;

public interface UserRepositoryJpa extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    Optional<UserEntity> findFirstByRole(UserRole role);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> findByIdLockingShared(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserEntity u set u.lastLoginAt = :now where u.id = :id")
    int markLogin(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserEntity u set u.passwordHash = :hash, u.passwordSetAt = :now, u.passwordSetupPending = false,
                   u.setupCodeHash = null, u.setupCodeExpiresAt = null, u.updatedAt = :now, u.version = u.version + 1
             where u.id = :id and u.passwordHash is null and u.setupCodeHash = :code
            """)
    int completeSetup(@Param("id") UUID id, @Param("code") String code, @Param("hash") String hash, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserEntity u set u.status = com.moneylog.core.auth.domain.UserStatus.DISABLED,
                   u.disabledAt = coalesce(u.disabledAt, :now), u.updatedAt = :now, u.version = u.version + 1
             where u.id = :id and u.status <> com.moneylog.core.auth.domain.UserStatus.DISABLED
            """)
    int disable(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserEntity u set u.status = com.moneylog.core.auth.domain.UserStatus.ACTIVE,
                   u.disabledAt = null, u.updatedAt = :now, u.version = u.version + 1
             where u.id = :id and u.status <> com.moneylog.core.auth.domain.UserStatus.ACTIVE
            """)
    int enable(@Param("id") UUID id, @Param("now") Instant now);
}
