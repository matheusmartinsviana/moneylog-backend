package com.moneylog.core.note.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.note.app.gateways.NoteTargetGateway;
import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetInfo;
import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetRef;
import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetStatus;
import com.moneylog.core.note.domain.NoteLink;
import com.moneylog.core.note.domain.NoteLinkType;
import com.moneylog.core.note.domain.NoteLimits;
import com.moneylog.core.note.exception.NoteExceptions.NoteLinkInvalidException;
import com.moneylog.core.note.exception.NoteExceptions.NoteLinkLimitReachedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteLinkTargetNotFoundException;

public final class ExplicitLinkValidator {

    private final NoteTargetGateway targets;

    public ExplicitLinkValidator(NoteTargetGateway targets) {
        this.targets = targets;
    }

    public List<NoteLink> validate(UUID userId, List<ExplicitLinkSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        if (specs.size() > NoteLimits.MAX_EXPLICIT_LINKS) {
            throw new NoteLinkLimitReachedException(NoteLimits.MAX_EXPLICIT_LINKS);
        }
        Set<TargetRef> refs = new LinkedHashSet<>();
        for (ExplicitLinkSpec spec : specs) {
            if (spec.type() == null) {
                throw new NoteLinkInvalidException("tipo ausente.");
            }
            if (spec.type().usesKey()) {
                if (spec.key() == null || spec.id() != null) {
                    throw new NoteLinkInvalidException("MONTHLY_SUMMARY usa 'key' (AAAA-MM) e não 'id'.");
                }
                if (!NoteLink.isValidMonth(spec.key())) {
                    throw new NoteLinkInvalidException("chave de resumo mensal inválida: " + spec.key() + " (use AAAA-MM de 2000-01 a 2100-12).");
                }
            } else {
                if (spec.id() == null || spec.key() != null) {
                    throw new NoteLinkInvalidException(spec.type() + " usa 'id' e não 'key'.");
                }
                refs.add(new TargetRef(spec.type(), spec.id()));
            }
        }
        Map<TargetRef, TargetInfo> found = refs.isEmpty() ? Map.of() : targets.describe(userId, refs);
        List<NoteLink> links = new ArrayList<>();
        for (ExplicitLinkSpec spec : specs) {
            if (spec.type().usesKey()) {
                links.add(NoteLink.explicitMonth(spec.key()));
                continue;
            }
            TargetInfo info = found.get(new TargetRef(spec.type(), spec.id()));
            if (info == null || (spec.type() == NoteLinkType.NOTE && info.status() == TargetStatus.TRASHED)) {
                throw new NoteLinkTargetNotFoundException(spec.type().name(), spec.id().toString());
            }
            links.add(NoteLink.explicit(spec.type(), spec.id()));
        }
        return links;
    }
}
