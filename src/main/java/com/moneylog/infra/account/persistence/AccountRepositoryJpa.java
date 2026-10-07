package com.moneylog.infra.account.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.moneylog.core.account.domain.AccountStatus;

public interface AccountRepositoryJpa extends JpaRepository<AccountEntity, UUID> {

    Optional<AccountEntity> findByIdAndUserId(UUID id, UUID userId);

    /** {@code SELECT ... FOR UPDATE} da linha (BUG-QA-08). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id and a.userId = :userId")
    Optional<AccountEntity> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    List<AccountEntity> findByUserId(UUID userId);

    @Query("""
            select a from AccountEntity a
            where a.userId = :userId and (:status is null or a.status = :status)
            order by lower(a.name), a.id""")
    List<AccountEntity> findPage(@Param("userId") UUID userId, @Param("status") AccountStatus status, Pageable page);
}
