package com.moneylog.core.note.app.usecase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.note.app.ExplicitLinkSpec;
import com.moneylog.core.note.app.ExplicitLinkValidator;
import com.moneylog.core.note.app.NoteView;
import com.moneylog.core.note.app.NoteViewAssembler;
import com.moneylog.core.note.app.VaultIndex;
import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteLimits;
import com.moneylog.core.note.domain.NoteLink;
import com.moneylog.core.note.domain.NotePath;
import com.moneylog.core.note.domain.WikilinkResolver;
import com.moneylog.core.note.exception.NoteExceptions.NoteLimitReachedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;

public class CreateNoteUseCaseImpl implements CreateNoteUseCase {

    private final NoteGateway notes;
    private final ExplicitLinkValidator validator;
    private final NoteViewAssembler views;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final com.moneylog.core.sk.time.TimeProvider time;

    public CreateNoteUseCaseImpl(NoteGateway notes, ExplicitLinkValidator validator, NoteViewAssembler views, IdempotencyGateway idempotency,
                                 IdGenerator ids, com.moneylog.core.sk.time.TimeProvider time) {
        this.notes = notes;
        this.validator = validator;
        this.views = views;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public NoteView handle(CreateNoteCommand c) {
        notes.lockVault(c.userId());
        UUID noteId = ids.next();
        if (c.idempotencyKey() != null) {
            String hash = hashOf(c);
            Claim existing = IdempotentClaim.claimOrFind(idempotency, c.userId(), IDEMPOTENCY_SCOPE, c.idempotencyKey(), hash, noteId, time.now());
            if (existing != null) {
                IdempotentClaim.requireSameCommand(existing, hash);
                Versioned<Note> current = notes.findById(c.userId(), existing.resourceId())
                        .orElseThrow(() -> new NoteNotFoundException(existing.resourceId()));
                return views.of(c.userId(), current);
            }
        }
        NotePath path = NotePath.of(c.path());
        List<WikilinkResolver.Entry> index = new ArrayList<>(notes.activeIndex(c.userId()));
        VaultIndex.requireFree(index, path, null);
        if (notes.countAll(c.userId()) >= NoteLimits.MAX_NOTES) {
            throw new NoteLimitReachedException(NoteLimits.MAX_NOTES);
        }
        List<NoteLink> explicit = validator.validate(c.userId(), c.links());
        index.add(new WikilinkResolver.Entry(noteId, path));
        Note note = Note.create(noteId, c.userId(), path, c.body(), explicit, new WikilinkResolver(index));
        Versioned<Note> saved = notes.save(note);
        VaultIndex.refreshWikilinks(notes, c.userId(), List.of(path));
        return views.of(c.userId(), saved);
    }

    private static String hashOf(CreateNoteCommand c) {
        StringBuilder canonical = new StringBuilder("path=").append(c.path()).append(";body=").append(IdempotentClaim.hash(String.valueOf(c.body())))
                .append(";links=");
        if (c.links() != null) {
            for (ExplicitLinkSpec link : c.links()) {
                canonical.append(link.type()).append(':').append(link.id()).append(':').append(link.key()).append(',');
            }
        }
        return IdempotentClaim.hash(canonical.toString());
    }
}
