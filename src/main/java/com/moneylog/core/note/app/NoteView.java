package com.moneylog.core.note.app;

import java.util.List;

import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetInfo;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteLink;
import com.moneylog.core.sk.persistence.Versioned;

/** A nota com o estado de cada vínculo (alvo resolvido com rótulo e estado, ou pendente). */
public record NoteView(Versioned<Note> note, List<LinkView> links) {

    /** Vínculo + o que se sabe do alvo ({@code null} se pendente ou se o alvo é um resumo mensal). */
    public record LinkView(NoteLink link, TargetInfo target) {
    }

    public NoteView {
        links = List.copyOf(links);
    }
}
