package com.moneylog.infra.auth.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface UserRepositoryJpa extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    @Query("select u from UserEntity u order by u.createdAt asc, u.id asc")
    List<UserEntity> findOldest(Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserEntity u set u.passwordHash = :hash, u.passwordSetAt = :now, u.passwordSetupPending = false,
                   u.setupCodeHash = null, u.setupCodeExpiresAt = null, u.updatedAt = :now, u.version = u.version + 1
             where u.id = :id and u.passwordHash is null and u.setupCodeHash = :code
            """)
    int completeSetup(@Param("id") UUID id, @Param("code") String code, @Param("hash") String hash, @Param("now") Instant now);
}
