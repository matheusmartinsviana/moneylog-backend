package com.moneylog.infra.note.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import com.moneylog.core.note.app.ExplicitLinkSpec;
import com.moneylog.core.note.app.NoteQuery;
import com.moneylog.core.note.app.NoteSort;
import com.moneylog.core.note.app.NoteStatus;
import com.moneylog.core.note.app.NoteView;
import com.moneylog.core.note.app.usecase.CreateNoteUseCase;
import com.moneylog.core.note.app.usecase.CreateNoteUseCase.CreateNoteCommand;
import com.moneylog.core.note.app.usecase.EmptyTrashUseCase;
import com.moneylog.core.note.app.usecase.EmptyTrashUseCase.EmptyTrashCommand;
import com.moneylog.core.note.app.usecase.GetNoteUseCase;
import com.moneylog.core.note.app.usecase.GetNoteUseCase.GetNoteQuery;
import com.moneylog.core.note.app.usecase.ListBacklinksUseCase;
import com.moneylog.core.note.app.usecase.ListBacklinksUseCase.ListBacklinksQuery;
import com.moneylog.core.note.app.usecase.ListNotesUseCase;
import com.moneylog.core.note.app.usecase.ListTagsUseCase;
import com.moneylog.core.note.app.usecase.ListTagsUseCase.ListTagsQuery;
import com.moneylog.core.note.app.usecase.MoveNoteUseCase;
import com.moneylog.core.note.app.usecase.MoveNoteUseCase.MoveNoteCommand;
import com.moneylog.core.note.app.usecase.RestoreNoteUseCase;
import com.moneylog.core.note.app.usecase.RestoreNoteUseCase.RestoreNoteCommand;
import com.moneylog.core.note.app.usecase.SetNoteLinksUseCase;
import com.moneylog.core.note.app.usecase.SetNoteLinksUseCase.SetNoteLinksCommand;
import com.moneylog.core.note.app.usecase.TrashNoteUseCase;
import com.moneylog.core.note.app.usecase.TrashNoteUseCase.TrashNoteCommand;
import com.moneylog.core.note.app.usecase.UpdateNoteUseCase;
import com.moneylog.core.note.app.usecase.UpdateNoteUseCase.UpdateNoteCommand;
import com.moneylog.core.note.domain.NoteLimits;
import com.moneylog.core.note.domain.NoteLinkType;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.note.controller.NoteRequests.LinkInput;
import com.moneylog.infra.note.controller.NoteRequests.NoteInput;
import com.moneylog.infra.note.controller.NoteRequests.NoteLinksUpdate;
import com.moneylog.infra.note.controller.NoteRequests.NoteMove;
import com.moneylog.infra.note.controller.NoteRequests.NoteUpdate;
import com.moneylog.infra.note.controller.NoteResponses.BacklinkResponse;
import com.moneylog.infra.note.controller.NoteResponses.NoteMovedResponse;
import com.moneylog.infra.note.controller.NoteResponses.NoteResponse;
import com.moneylog.infra.note.controller.NoteResponses.NoteSummaryResponse;
import com.moneylog.infra.note.controller.NoteResponses.TagCountResponse;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.SliceResponse;
import com.moneylog.infra.web.validation.NoControlChars;

@RestController
@RequestMapping("/v1/notes")
@Validated
public class NoteController {

    private final CreateNoteUseCase create;
    private final GetNoteUseCase get;
    private final UpdateNoteUseCase update;
    private final MoveNoteUseCase move;
    private final TrashNoteUseCase trash;
    private final RestoreNoteUseCase restore;
    private final EmptyTrashUseCase emptyTrash;
    private final SetNoteLinksUseCase setLinks;
    private final ListNotesUseCase list;
    private final ListBacklinksUseCase backlinks;
    private final ListTagsUseCase tags;

    @SuppressWarnings("java:S107")
    public NoteController(CreateNoteUseCase create, GetNoteUseCase get, UpdateNoteUseCase update, MoveNoteUseCase move, TrashNoteUseCase trash,
                          RestoreNoteUseCase restore, EmptyTrashUseCase emptyTrash, SetNoteLinksUseCase setLinks, ListNotesUseCase list,
                          ListBacklinksUseCase backlinks, ListTagsUseCase tags) {
        this.create = create;
        this.get = get;
        this.update = update;
        this.move = move;
        this.trash = trash;
        this.restore = restore;
        this.emptyTrash = emptyTrash;
        this.setLinks = setLinks;
        this.list = list;
        this.backlinks = backlinks;
        this.tags = tags;
    }

    @GetMapping
    public SliceResponse<NoteSummaryResponse> list(@CurrentUser UUID userId,
                                                   @RequestParam(required = false) @Size(max = NoteLimits.QUERY_MAX) @NoControlChars String q,
                                                   @RequestParam(required = false) @Size(max = 64) @NoControlChars String tag,
                                                   @RequestParam(required = false) @Size(max = 255) @NoControlChars String folder,
                                                   @RequestParam(required = false) NoteLinkType linkedType,
                                                   @RequestParam(required = false) UUID linkedId,
                                                   @RequestParam(required = false) @Size(max = 7) String linkedKey,
                                                   @RequestParam(required = false) NoteStatus status,
                                                   @RequestParam(required = false) NoteSort sort, PageQuery page) {
        requireLinkFilter(linkedType, linkedId, linkedKey, "linkedType", "linkedId", "linkedKey");
        return SliceResponse.of(list.handle(new NoteQuery(userId, q, tag, folder, linkedType, linkedId, linkedKey, status, sort, page)),
                NoteSummaryResponse::of);
    }

    @PostMapping
    public ResponseEntity<NoteResponse> create(@CurrentUser UUID userId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                               @Valid @RequestBody NoteInput body) {
        NoteView created = create.handle(new CreateNoteCommand(userId, body.path(), body.body(), specs(body.links()), idempotencyKey));
        return ResponseEntity.created(URI.create("/v1/notes/" + created.note().value().id())).body(NoteResponse.of(created));
    }

    @GetMapping("/tags")
    public List<TagCountResponse> tags(@CurrentUser UUID userId) {
        return tags.handle(new ListTagsQuery(userId)).stream().map(TagCountResponse::of).toList();
    }

    @DeleteMapping("/trash")
    public ResponseEntity<Void> emptyTrash(@CurrentUser UUID userId) {
        emptyTrash.handle(new EmptyTrashCommand(userId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public NoteResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return NoteResponse.of(get.handle(new GetNoteQuery(userId, id)));
    }

    @PutMapping("/{id}")
    public NoteResponse update(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody NoteUpdate body) {
        return NoteResponse.of(update.handle(new UpdateNoteCommand(userId, id, body.body(), body.version())));
    }

    @PostMapping("/{id}/move")
    public NoteMovedResponse move(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody NoteMove body) {
        return NoteMovedResponse.of(move.handle(new MoveNoteCommand(userId, id, body.newPath(), body.version())));
    }

    @PostMapping("/{id}/trash")
    public NoteResponse trash(@CurrentUser UUID userId, @PathVariable UUID id) {
        return NoteResponse.of(trash.handle(new TrashNoteCommand(userId, id)));
    }

    @PostMapping("/{id}/restore")
    public NoteResponse restore(@CurrentUser UUID userId, @PathVariable UUID id) {
        return NoteResponse.of(restore.handle(new RestoreNoteCommand(userId, id)));
    }

    @GetMapping("/{id}/backlinks")
    public SliceResponse<BacklinkResponse> backlinks(@CurrentUser UUID userId, @PathVariable UUID id, PageQuery page) {
        return SliceResponse.of(backlinks.handle(new ListBacklinksQuery(userId, id, page)), BacklinkResponse::of);
    }

    @PutMapping("/{id}/links")
    public NoteResponse links(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody NoteLinksUpdate body) {
        return NoteResponse.of(setLinks.handle(new SetNoteLinksCommand(userId, id, specs(body.links()), body.version())));
    }

    static List<ExplicitLinkSpec> specs(List<LinkInput> links) {
        return links == null ? List.of() : links.stream().map(l -> new ExplicitLinkSpec(l.type(), l.id(), l.key())).toList();
    }

    static void requireLinkFilter(NoteLinkType type, UUID id, String key, String typeField, String idField, String keyField) {
        if (type == null) {
            if (id != null || key != null) {
                throw new RequestValidationException(typeField, "REQUIRED", "'" + typeField + "' é obrigatório com '" + idField + "' ou '" + keyField + "'.");
            }
            return;
        }
        if (type.usesKey()) {
            if (key == null || id != null) {
                throw new RequestValidationException(keyField, "REQUIRED", type + " usa '" + keyField + "' (AAAA-MM) e não '" + idField + "'.");
            }
        } else if (id == null || key != null) {
            throw new RequestValidationException(idField, "REQUIRED", type + " usa '" + idField + "' e não '" + keyField + "'.");
        }
    }
}
