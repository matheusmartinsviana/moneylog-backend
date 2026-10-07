package com.moneylog.infra.auth.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepositoryJpa extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    @Query("select u from UserEntity u order by u.createdAt asc, u.id asc")
    List<UserEntity> findOldest(Pageable pageable);
}
