package com.moneylog.core.note.util;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.note.app.gateways.NoteTargetGateway;

public class InMemoryNoteTargetGateway implements NoteTargetGateway {

    private final Map<UUID, Map<TargetRef, TargetInfo>> byUser = new HashMap<>();
    private final InMemoryNoteGateway notes;

    public InMemoryNoteTargetGateway() {
        this(null);
    }

    public InMemoryNoteTargetGateway(InMemoryNoteGateway notes) {
        this.notes = notes;
    }

    public void add(UUID userId, TargetRef ref, TargetInfo info) {
        byUser.computeIfAbsent(userId, k -> new HashMap<>()).put(ref, info);
    }

    @Override
    public Map<TargetRef, TargetInfo> describe(UUID userId, Collection<TargetRef> refs) {
        Map<TargetRef, TargetInfo> known = byUser.getOrDefault(userId, Map.of());
        Map<TargetRef, TargetInfo> out = new HashMap<>();
        refs.stream().filter(known::containsKey).forEach(r -> out.put(r, known.get(r)));
        if (notes != null) {
            refs.stream().filter(r -> r.type() == com.moneylog.core.note.domain.NoteLinkType.NOTE).forEach(r -> notes.findById(userId, r.id())
                    .ifPresent(n -> out.put(r, new TargetInfo(n.value().title(), n.value().isTrashed() ? TargetStatus.TRASHED : TargetStatus.ACTIVE))));
        }
        return out;
    }
}
