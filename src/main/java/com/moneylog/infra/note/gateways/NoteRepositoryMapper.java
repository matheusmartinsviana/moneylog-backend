package com.moneylog.infra.note.gateways;

import java.util.Collection;
import java.util.List;

import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteLink;
import com.moneylog.core.note.domain.NotePath;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.infra.note.persistence.NoteEntity;

public final class NoteRepositoryMapper {

    private NoteRepositoryMapper() {
    }

    public static Note toDomain(NoteEntity e, Collection<String> tags, List<NoteLink> links) {
        return Note.restore(e.getId(), e.getUserId(), NotePath.of(e.getPath()), e.getTitle(), e.getBody(), new java.util.LinkedHashSet<>(tags), links,
                e.getDeletedAt());
    }

    public static Versioned<Note> toVersioned(NoteEntity e, Collection<String> tags, List<NoteLink> links) {
        return new Versioned<>(toDomain(e, tags, links), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    public static NoteEntity toEntity(Note n, java.time.Instant now) {
        return new NoteEntity(n.id(), n.userId(), n.path().value(), n.path().key(), n.title(), n.body(), n.trashedAt().orElse(null), now);
    }
}
