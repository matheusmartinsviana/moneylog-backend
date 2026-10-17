package com.moneylog.infra.category.persistence;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.moneylog.core.category.domain.CategoryKind;

public interface CategoryRepositoryJpa extends JpaRepository<CategoryEntity, UUID> {

    Optional<CategoryEntity> findByIdAndUserId(UUID id, UUID userId);

    List<CategoryEntity> findByUserId(UUID userId);

    Optional<CategoryEntity> findByUserIdAndSystemKey(UUID userId, String systemKey);

    @Query("""
            select c from CategoryEntity c
            where c.userId = :userId and (:includeArchived = true or c.archived = false)
            order by lower(c.name), c.id""")
    List<CategoryEntity> findAllOrdered(@Param("userId") UUID userId, @Param("includeArchived") boolean includeArchived);

    @Query("""
            select c from CategoryEntity c
            where c.userId = :userId and c.kind = :kind and (:includeArchived = true or c.archived = false)
            order by lower(c.name), c.id""")
    List<CategoryEntity> findByKindOrdered(@Param("userId") UUID userId, @Param("kind") CategoryKind kind,
                                           @Param("includeArchived") boolean includeArchived);

    @Query("""
            select count(c) > 0 from CategoryEntity c
            where c.userId = :userId and c.kind = :kind and c.archived = false
              and lower(c.name) = lower(:name) and c.id <> :excludingId""")
    boolean existsActiveWithName(@Param("userId") UUID userId, @Param("kind") CategoryKind kind, @Param("name") String name,
                                 @Param("excludingId") UUID excludingId);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CategoryEntity c where c.userId = :userId")
    void deleteAllOf(@Param("userId") UUID userId);

    @Query("select c.systemKey from CategoryEntity c where c.userId = :userId and c.systemKey is not null")
    Set<String> findSystemKeys(@Param("userId") UUID userId);
}
