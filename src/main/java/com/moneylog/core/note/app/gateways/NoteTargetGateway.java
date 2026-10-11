package com.moneylog.core.note.app.gateways;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.note.domain.NoteLinkType;

public interface NoteTargetGateway {

    record TargetRef(NoteLinkType type, UUID id) {
    }

    enum TargetStatus {
        ACTIVE, VOIDED, ARCHIVED, CANCELED, TRASHED
    }

    record TargetInfo(String label, TargetStatus status) {
    }

    Map<TargetRef, TargetInfo> describe(UUID userId, Collection<TargetRef> refs);
}
