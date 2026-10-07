package com.moneylog.core.note.app.usecase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.note.app.NoteView;
import com.moneylog.core.note.app.NoteViewAssembler;
import com.moneylog.core.note.app.VaultIndex;
import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.domain.Frontmatter;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteLimits;
import com.moneylog.core.note.domain.NotePath;
import com.moneylog.core.note.domain.Wikilink;
import com.moneylog.core.note.domain.WikilinkResolver;
import com.moneylog.core.note.domain.WikilinkRewriter;
import com.moneylog.core.note.exception.NoteExceptions.NoteNotFoundException;
import com.moneylog.core.note.exception.NoteExceptions.NoteRewriteTooLargeException;
import com.moneylog.core.note.exception.NoteExceptions.NoteTooLargeException;
import com.moneylog.core.note.exception.NoteExceptions.NoteTrashedException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Versioned;

public class MoveNoteUseCaseImpl implements MoveNoteUseCase {

    private final NoteGateway notes;
    private final NoteViewAssembler views;

    public MoveNoteUseCaseImpl(NoteGateway notes, NoteViewAssembler views) {
        this.notes = notes;
        this.views = views;
    }

    @Override
    public MoveNoteResult handle(MoveNoteCommand c) {
        notes.lockVault(c.userId());
        Versioned<Note> current = notes.findById(c.userId(), c.noteId()).orElseThrow(() -> new NoteNotFoundException(c.noteId()));
        if (current.version() != c.version()) {
            throw new VersionConflictException(c.version(), current.version());
        }
        Note moved = current.value();
        if (moved.isTrashed()) {
            throw new NoteTrashedException();
        }
        NotePath newPath = NotePath.of(c.newPath());
        if (newPath.equals(moved.path())) {
            return new MoveNoteResult(views.of(c.userId(), current), List.of());
        }
        List<WikilinkResolver.Entry> index = notes.activeIndex(c.userId());
        VaultIndex.requireFree(index, newPath, moved.id());
        WikilinkResolver before = new WikilinkResolver(index);
        List<WikilinkResolver.Entry> afterIndex = new ArrayList<>();
        for (WikilinkResolver.Entry entry : index) {
            afterIndex.add(entry.id().equals(moved.id()) ? new WikilinkResolver.Entry(moved.id(), newPath) : entry);
        }
        WikilinkResolver after = new WikilinkResolver(afterIndex);

        // só os links que apontavam para ESTA nota: a resolução gravada é a de antes da mudança
        Set<UUID> sources = new HashSet<>(notes.noteIdsLinkingTo(c.userId(), moved.id()));
        sources.remove(moved.id());
        List<RewrittenNote> rewritten = new ArrayList<>();
        for (Versioned<Note> source : notes.findByIds(c.userId(), sources)) {
            Note note = source.value();
            String text = rewrite(note.body(), moved, newPath, before, after);
            if (!text.equals(note.body())) {
                try {
                    note.rewriteBody(text, after);
                } catch (NoteTooLargeException tooLarge) {
                    // o nome novo, mais longo, faria ESTA outra nota passar de 200 KB: o erro precisa dizer qual (nada é gravado)
                    throw new NoteRewriteTooLargeException(note.path().value(), NoteLimits.NOTE_MAX_BYTES);
                }
                notes.save(note);
                rewritten.add(new RewrittenNote(note.id(), note.path().value(), note.title()));
            }
        }
        // links da própria nota para ela mesma
        String ownText = rewrite(moved.body(), moved, newPath, before, after);
        if (!ownText.equals(moved.body())) {
            moved.rewriteBody(ownText, after);
        }
        NotePath oldPath = moved.path();
        moved.move(newPath);
        Versioned<Note> saved = notes.save(moved);
        VaultIndex.refreshWikilinks(notes, c.userId(), List.of(oldPath, newPath));
        Versioned<Note> fresh = notes.findById(c.userId(), saved.value().id()).orElse(saved);
        return new MoveNoteResult(views.of(c.userId(), fresh), rewritten);
    }

    private static String rewrite(String text, Note moved, NotePath newPath, WikilinkResolver before, WikilinkResolver after) {
        int from = Frontmatter.parse(text).bodyStart();
        return WikilinkRewriter.rewrite(text, from, link -> before.resolve(link).filter(moved.id()::equals)
                .map(id -> newTarget(link, newPath, after, moved.id())).orElse(null));
    }

    /** Preserva a forma escrita (nome ou caminho, com ou sem {@code .md}) e garante que o novo alvo resolve para a nota movida. */
    static String newTarget(Wikilink link, NotePath newPath, WikilinkResolver after, UUID movedId) {
        String candidate = link.isPathForm() ? newPath.withoutExtension() : newPath.name();
        if (!link.isPathForm() && !after.resolve(candidate).filter(movedId::equals).isPresent()) {
            candidate = newPath.withoutExtension();
        }
        return link.hasMarkdownExtension() ? candidate + NotePath.EXTENSION : candidate;
    }
}
