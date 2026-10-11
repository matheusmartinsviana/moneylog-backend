package com.moneylog.infra.note.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.note.app.Backlink;
import com.moneylog.core.note.app.NoteSummary;
import com.moneylog.core.note.app.NoteView;
import com.moneylog.core.note.app.NoteView.LinkView;
import com.moneylog.core.note.app.TagCount;
import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetInfo;
import com.moneylog.core.note.app.usecase.ImportVaultUseCase.ImportReport;
import com.moneylog.core.note.app.usecase.MoveNoteUseCase.MoveNoteResult;
import com.moneylog.core.note.domain.LinkOrigin;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteLink;
import com.moneylog.core.note.domain.NoteLinkType;
import com.moneylog.core.sk.persistence.Versioned;

public final class NoteResponses {

    private NoteResponses() {
    }

    public record LinkTarget(UUID id, String key, String label, String status) {
    }

    public record NoteLinkResponse(LinkOrigin origin, NoteLinkType type, String state, String text, String alias, String section, boolean embed,
                                   LinkTarget target) {
        static NoteLinkResponse of(LinkView view) {
            NoteLink link = view.link();
            TargetInfo info = view.target();
            LinkTarget target = null;
            if (link.targetKey() != null) {
                target = new LinkTarget(null, link.targetKey(), link.targetKey(), null);
            } else if (link.targetId() != null) {
                target = new LinkTarget(link.targetId(), null, info == null ? null : info.label(),
                        info == null || info.status() == null ? null : info.status().name());
            }
            return new NoteLinkResponse(link.origin(), link.type(), link.state().name(), link.text(), link.alias(), link.section(), link.embed(), target);
        }
    }

    public record NoteResponse(UUID id, String path, String title, String body, List<String> tags, List<NoteLinkResponse> links, long version,
                               Instant trashedAt, Instant createdAt, Instant updatedAt) {
        public static NoteResponse of(NoteView view) {
            Versioned<Note> versioned = view.note();
            Note note = versioned.value();
            return new NoteResponse(note.id(), note.path().value(), note.title(), note.body(), note.tags().stream().sorted().toList(),
                    view.links().stream().map(NoteLinkResponse::of).toList(), versioned.version(), note.trashedAt().orElse(null),
                    versioned.createdAt(), versioned.updatedAt());
        }
    }

    public record NoteSummaryResponse(UUID id, String path, String title, List<String> tags, String snippet, Instant updatedAt, Instant trashedAt,
                                      long version) {
        public static NoteSummaryResponse of(NoteSummary s) {
            return new NoteSummaryResponse(s.id(), s.path(), s.title(), s.tags(), s.snippet(), s.updatedAt(), s.trashedAt(), s.version());
        }
    }

    public record BacklinkResponse(UUID noteId, String path, String title, String context) {
        public static BacklinkResponse of(Backlink b) {
            return new BacklinkResponse(b.noteId(), b.path(), b.title(), b.context());
        }
    }

    public record TagCountResponse(String tag, long count) {
        public static TagCountResponse of(TagCount t) {
            return new TagCountResponse(t.tag(), t.count());
        }
    }

    public record NoteRefResponse(UUID id, String path, String title) {
    }

    public record NoteMovedResponse(NoteResponse note, List<NoteRefResponse> rewrittenNotes) {
        public static NoteMovedResponse of(MoveNoteResult result) {
            return new NoteMovedResponse(NoteResponse.of(result.note()),
                    result.rewrittenNotes().stream().map(r -> new NoteRefResponse(r.id(), r.path(), r.title())).toList());
        }
    }

    public record RenamedFileResponse(String from, String to) {
    }

    public record ImportWarningResponse(String path, String code, String message) {
    }

    public record ImportReportResponse(int created, int skipped, int pendingLinks, List<String> ignoredFiles, List<String> skippedFiles,
                                       List<RenamedFileResponse> renamedFiles, List<ImportWarningResponse> warnings) {
        public static ImportReportResponse of(ImportReport r) {
            return new ImportReportResponse(r.created(), r.skipped(), r.pendingLinks(), r.ignoredFiles(), r.skippedFiles(),
                    r.renamedFiles().stream().map(f -> new RenamedFileResponse(f.from(), f.to())).toList(),
                    r.warnings().stream().map(w -> new ImportWarningResponse(w.path(), w.code(), w.message())).toList());
        }
    }
}
