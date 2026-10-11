package com.moneylog.core.note.app;

import java.util.UUID;

import com.moneylog.core.note.domain.NoteLinkType;

public record ExplicitLinkSpec(NoteLinkType type, UUID id, String key) {
}
