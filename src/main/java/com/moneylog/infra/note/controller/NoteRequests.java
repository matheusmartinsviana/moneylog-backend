package com.moneylog.infra.note.controller;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.note.domain.NoteLinkType;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Corpos de entrada de {@code /v1/notes} (contrato: {@code NoteInput}, {@code NoteUpdate}, {@code NoteMove}, {@code NoteLinksUpdate}). O texto da
 * nota aceita quebra de linha e tabulação; o tamanho (200 KB em bytes) é regra de domínio ({@code 422 NOTE_TOO_LARGE}).
 */
public final class NoteRequests {

    private NoteRequests() {
    }

    public record LinkInput(@NotNull NoteLinkType type, UUID id, @Size(max = 7) String key) {
    }

    public record NoteInput(@NotBlank @Size(max = 600) @NoControlChars String path,
                            @NotNull @NoControlChars(allowLineBreaks = true) String body,
                            @Size(max = 100) List<@Valid @NotNull LinkInput> links) {
    }

    public record NoteUpdate(@NotNull @NoControlChars(allowLineBreaks = true) String body, @NotNull Long version) {
    }

    public record NoteMove(@NotBlank @Size(max = 600) @NoControlChars String newPath, @NotNull Long version) {
    }

    public record NoteLinksUpdate(@NotNull @Size(max = 100) List<@Valid @NotNull LinkInput> links, @NotNull Long version) {
    }
}
