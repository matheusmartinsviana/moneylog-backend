package com.moneylog.core.note.app;

import java.util.UUID;

import com.moneylog.core.note.domain.NoteLinkType;

/** Vínculo pedido pelo usuário: {@code id} para entidades e notas, {@code key} ({@code AAAA-MM}) para o resumo mensal. */
public record ExplicitLinkSpec(NoteLinkType type, UUID id, String key) {
}
