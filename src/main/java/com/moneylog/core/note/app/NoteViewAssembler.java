package com.moneylog.core.note.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.note.app.NoteView.LinkView;
import com.moneylog.core.note.app.gateways.NoteTargetGateway;
import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetInfo;
import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetRef;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteLink;
import com.moneylog.core.sk.persistence.Versioned;

public final class NoteViewAssembler {

    private final NoteTargetGateway targets;

    public NoteViewAssembler(NoteTargetGateway targets) {
        this.targets = targets;
    }

    public NoteView of(UUID userId, Versioned<Note> versioned) {
        List<NoteLink> links = versioned.value().links();
        Set<TargetRef> refs = new LinkedHashSet<>();
        for (NoteLink link : links) {
            if (link.targetId() != null) {
                refs.add(new TargetRef(link.type(), link.targetId()));
            }
        }
        Map<TargetRef, TargetInfo> info = refs.isEmpty() ? Map.of() : targets.describe(userId, refs);
        List<LinkView> views = new ArrayList<>();
        for (NoteLink link : links) {
            TargetInfo target = link.targetId() == null ? null : info.get(new TargetRef(link.type(), link.targetId()));
            views.add(new LinkView(link, target));
        }
        return new NoteView(versioned, views);
    }
}
