package com.moneylog.core.note.util;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.moneylog.core.note.app.Backlink;
import com.moneylog.core.note.app.NoteQuery;
import com.moneylog.core.note.app.NoteSummary;
import com.moneylog.core.note.app.StoredWikilink;
import com.moneylog.core.note.app.TagCount;
import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteLink;
import com.moneylog.core.note.domain.WikilinkResolver;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

public class InMemoryNoteGateway implements NoteGateway {

    private final Map<UUID, Note> notes = new LinkedHashMap<>();
    private final Map<UUID, Long> versions = new HashMap<>();
    private final Instant now;
    private final List<String> calls = new ArrayList<>();
    private int saves;
    private int failOnSave = -1;
    private Map<UUID, Note> snapshotForRollback;

    public InMemoryNoteGateway(Instant now) {
        this.now = now;
    }

    public List<String> calls() {
        return calls;
    }

    public int saves() {
        return saves;
    }

    public void failOnSave(int n) {
        this.failOnSave = n;
    }

    public void begin() {
        snapshotForRollback = new LinkedHashMap<>();
        notes.forEach((id, n) -> snapshotForRollback.put(id, copy(n)));
    }

    public void rollback() {
        notes.clear();
        snapshotForRollback.forEach((id, n) -> notes.put(id, copy(n)));
    }

    public Collection<Note> all() {
        return notes.values().stream().map(InMemoryNoteGateway::copy).toList();
    }

    public Note get(UUID id) {
        return copy(notes.get(id));
    }

    @Override
    public void lockVault(UUID userId) {
        calls.add("lock");
    }

    @Override
    public Versioned<Note> save(Note note) {
        calls.add("save");
        saves++;
        if (saves == failOnSave) {
            throw new IllegalStateException("falha injetada na gravação " + saves);
        }
        long version = versions.merge(note.id(), 0L, (old, ignored) -> old + 1);
        notes.put(note.id(), copy(note));
        return new Versioned<>(copy(note), version, now, now);
    }

    @Override
    public Optional<Versioned<Note>> findById(UUID userId, UUID noteId) {
        Note note = notes.get(noteId);
        return note == null || !note.userId().equals(userId) ? Optional.empty()
                : Optional.of(new Versioned<>(copy(note), versions.get(noteId), now, now));
    }

    @Override
    public List<Versioned<Note>> findByIds(UUID userId, Collection<UUID> noteIds) {
        return noteIds.stream().map(id -> findById(userId, id)).flatMap(Optional::stream).toList();
    }

    @Override
    public List<WikilinkResolver.Entry> activeIndex(UUID userId) {
        return notes.values().stream().filter(n -> n.userId().equals(userId) && !n.isTrashed())
                .map(n -> new WikilinkResolver.Entry(n.id(), n.path())).toList();
    }

    @Override
    public Map<UUID, String> trashedPaths(UUID userId) {
        Map<UUID, String> out = new HashMap<>();
        notes.values().stream().filter(n -> n.userId().equals(userId) && n.isTrashed()).forEach(n -> out.put(n.id(), n.path().value()));
        return out;
    }

    @Override
    public int countAll(UUID userId) {
        return (int) notes.values().stream().filter(n -> n.userId().equals(userId)).count();
    }

    public static UUID linkId(UUID noteId, int index) {
        return UUID.nameUUIDFromBytes((noteId + "#" + index).getBytes());
    }

    private final List<java.util.Set<String>> nameKeyQueries = new ArrayList<>();
    private int wikilinkRowsRead;

    public List<java.util.Set<String>> nameKeyQueries() {
        return nameKeyQueries;
    }

    public int wikilinkRowsRead() {
        return wikilinkRowsRead;
    }

    @Override
    public List<StoredWikilink> wikilinksByName(UUID userId, Collection<String> nameKeys) {
        nameKeyQueries.add(new java.util.HashSet<>(nameKeys));
        List<StoredWikilink> out = new ArrayList<>();
        for (Note note : notes.values()) {
            if (!note.userId().equals(userId)) {
                continue;
            }
            List<NoteLink> links = note.wikilinks();
            for (int i = 0; i < links.size(); i++) {
                String key = WikilinkResolver.nameKey(links.get(i).text());
                if (nameKeys.contains(key)) {
                    out.add(new StoredWikilink(linkId(note.id(), i), note.id(), links.get(i).text(), links.get(i).targetId(), key));
                }
            }
        }
        wikilinkRowsRead += out.size();
        return out;
    }

    @Override
    public void updateWikilinks(UUID userId, List<com.moneylog.core.note.app.WikilinkUpdate> updates) {
        calls.add("updateWikilinks");
        Map<UUID, UUID> targetByLinkId = new HashMap<>();
        updates.forEach(u -> targetByLinkId.put(u.linkId(), u.targetNoteId()));
        for (Note note : new ArrayList<>(notes.values())) {
            List<NoteLink> links = new ArrayList<>();
            List<NoteLink> wikilinks = note.wikilinks();
            for (int i = 0; i < wikilinks.size(); i++) {
                UUID linkId = linkId(note.id(), i);
                links.add(targetByLinkId.containsKey(linkId) ? wikilinks.get(i).withTarget(targetByLinkId.get(linkId)) : wikilinks.get(i));
            }
            links.addAll(note.explicitLinks());
            notes.put(note.id(), Note.restore(note.id(), note.userId(), note.path(), note.title(), note.body(), note.tags(), links,
                    note.trashedAt().orElse(null)));
        }
    }

    @Override
    public List<UUID> noteIdsLinkingTo(UUID userId, UUID targetNoteId) {
        return notes.values().stream().filter(n -> n.userId().equals(userId)
                && n.wikilinks().stream().anyMatch(l -> targetNoteId.equals(l.targetId()))).map(Note::id).toList();
    }

    @Override
    public Slice<NoteSummary> search(NoteQuery query) {
        throw new UnsupportedOperationException("busca é do gateway real");
    }

    @Override
    public Slice<Backlink> backlinks(UUID userId, UUID noteId, PageQuery page) {
        throw new UnsupportedOperationException("backlinks são do gateway real");
    }

    @Override
    public List<TagCount> tagCounts(UUID userId) {
        throw new UnsupportedOperationException("tags são do gateway real");
    }

    @Override
    public int countPendingLinks(UUID userId, Collection<UUID> noteIds) {
        int count = 0;
        for (UUID id : noteIds) {
            Note note = notes.get(id);
            for (NoteLink link : note.links()) {
                if (link.state() == NoteLink.State.PENDING) {
                    count++;
                }
            }
        }
        return count;
    }

    @Override
    public int deleteTrashed(UUID userId) {
        List<UUID> trashed = notes.values().stream().filter(n -> n.userId().equals(userId) && n.isTrashed()).map(Note::id).toList();
        trashed.forEach(notes::remove);
        return trashed.size();
    }

    @Override
    public List<Versioned<Note>> findActiveBatch(UUID userId, UUID afterId, int limit) {
        TreeMap<UUID, Note> sorted = new TreeMap<>();
        notes.values().stream().filter(n -> n.userId().equals(userId) && !n.isTrashed()).forEach(n -> sorted.put(n.id(), n));
        List<Versioned<Note>> out = new ArrayList<>();
        for (Note n : sorted.values()) {
            if ((afterId == null || n.id().compareTo(afterId) > 0) && out.size() < limit) {
                out.add(new Versioned<>(copy(n), versions.get(n.id()), now, now));
            }
        }
        return out;
    }

    private static Note copy(Note n) {
        List<NoteLink> links = new ArrayList<>(n.wikilinks());
        links.addAll(n.explicitLinks());
        return Note.restore(n.id(), n.userId(), n.path(), n.title(), n.body(), Set.copyOf(n.tags()), links, n.trashedAt().orElse(null));
    }
}
