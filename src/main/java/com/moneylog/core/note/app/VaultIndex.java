package com.moneylog.core.note.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.domain.NotePath;
import com.moneylog.core.note.domain.WikilinkResolver;
import com.moneylog.core.note.exception.NoteExceptions.NotePathTakenException;

public final class VaultIndex {

    private VaultIndex() {
    }

    public static void refreshWikilinks(NoteGateway notes, UUID userId, Collection<NotePath> affectedPaths) {
        refreshWikilinks(notes, userId, affectedPaths, Set.of());
    }

    public static void refreshWikilinks(NoteGateway notes, UUID userId, Collection<NotePath> affectedPaths, Collection<String> extraNameKeys) {
        Set<String> keys = new HashSet<>(extraNameKeys);
        for (NotePath path : affectedPaths) {
            keys.add(WikilinkResolver.nameKey(path));
        }
        if (keys.isEmpty()) {
            return;
        }
        WikilinkResolver resolver = new WikilinkResolver(notes.activeIndex(userId));
        List<WikilinkUpdate> changes = new ArrayList<>();
        for (StoredWikilink stored : notes.wikilinksByName(userId, keys)) {
            UUID now = resolver.resolve(stored.text()).orElse(null);
            String key = stored.nameKey() != null ? stored.nameKey() : WikilinkResolver.nameKey(stored.text());
            if (!Objects.equals(now, stored.targetNoteId()) || stored.nameKey() == null) {
                changes.add(new WikilinkUpdate(stored.linkId(), now, key));
            }
        }
        if (!changes.isEmpty()) {
            notes.updateWikilinks(userId, changes);
        }
    }

    public static void requireFree(List<WikilinkResolver.Entry> index, NotePath path, UUID exceptId) {
        String key = path.key();
        for (WikilinkResolver.Entry entry : index) {
            if (!entry.id().equals(exceptId) && entry.path().key().equals(key)) {
                throw new NotePathTakenException(path.value());
            }
        }
    }
}
