package com.moneylog.core.note.app;

import java.util.List;

import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetInfo;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteLink;
import com.moneylog.core.sk.persistence.Versioned;

public record NoteView(Versioned<Note> note, List<LinkView> links) {

    public record LinkView(NoteLink link, TargetInfo target) {
    }

    public NoteView {
        links = List.copyOf(links);
    }
}
