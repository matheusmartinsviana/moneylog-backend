package com.moneylog.core.note.app.gateways;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.note.app.Backlink;
import com.moneylog.core.note.app.NoteQuery;
import com.moneylog.core.note.app.NoteSummary;
import com.moneylog.core.note.app.StoredWikilink;
import com.moneylog.core.note.app.TagCount;
import com.moneylog.core.note.app.WikilinkUpdate;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.WikilinkResolver;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

public interface NoteGateway {

    void lockVault(UUID userId);

    Versioned<Note> save(Note note);

    Optional<Versioned<Note>> findById(UUID userId, UUID noteId);

    List<Versioned<Note>> findByIds(UUID userId, Collection<UUID> noteIds);

    List<WikilinkResolver.Entry> activeIndex(UUID userId);

    java.util.Map<UUID, String> trashedPaths(UUID userId);

    int countAll(UUID userId);

    List<StoredWikilink> wikilinksByName(UUID userId, Collection<String> nameKeys);

    void updateWikilinks(UUID userId, List<WikilinkUpdate> updates);

    List<UUID> noteIdsLinkingTo(UUID userId, UUID targetNoteId);

    Slice<NoteSummary> search(NoteQuery query);

    Slice<Backlink> backlinks(UUID userId, UUID noteId, PageQuery page);

    List<TagCount> tagCounts(UUID userId);

    int countPendingLinks(UUID userId, Collection<UUID> noteIds);

    int deleteTrashed(UUID userId);

    List<Versioned<Note>> findActiveBatch(UUID userId, UUID afterId, int limit);
}
