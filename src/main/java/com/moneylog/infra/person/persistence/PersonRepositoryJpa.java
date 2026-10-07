package com.moneylog.infra.person.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonRepositoryJpa extends JpaRepository<PersonEntity, UUID> {

    Optional<PersonEntity> findByIdAndUserId(UUID id, UUID userId);

    /** {@code SELECT ... FOR UPDATE} da linha. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PersonEntity p where p.id = :id and p.userId = :userId")
    Optional<PersonEntity> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    List<PersonEntity> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    @Query("""
            select p from PersonEntity p
            where p.userId = :userId and (:archived is null or p.archived = :archived)
            order by lower(p.name), p.id""")
    List<PersonEntity> findPage(@Param("userId") UUID userId, @Param("archived") Boolean archived, Pageable page);
}
