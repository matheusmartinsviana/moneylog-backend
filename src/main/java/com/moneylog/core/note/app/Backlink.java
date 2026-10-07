package com.moneylog.core.note.app;

import java.util.UUID;

/** Uma nota que aponta para outra. {@code context} é o trecho (até 160 caracteres, texto puro) ao redor do link; {@code null} no vínculo explícito. */
public record Backlink(UUID noteId, String path, String title, String context) {
}
