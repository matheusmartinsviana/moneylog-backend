package com.moneylog.infra.note.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteRepositoryJpa extends JpaRepository<NoteEntity, UUID> {

    Optional<NoteEntity> findByIdAndUserId(UUID id, UUID userId);

    List<NoteEntity> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

    long countByUserId(UUID userId);
}
