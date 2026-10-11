package com.moneylog.core.note.app.usecase;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.note.app.VaultIndex;
import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.app.gateways.NoteTargetGateway;
import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetRef;
import com.moneylog.core.note.app.vault.VaultZipReader;
import com.moneylog.core.note.app.vault.VaultZipReader.IgnoreReason;
import com.moneylog.core.note.app.vault.VaultZipReader.IgnoredEntry;
import com.moneylog.core.note.app.vault.VaultZipReader.NoteEntry;
import com.moneylog.core.note.app.vault.VaultZipReader.Scan;
import com.moneylog.core.note.domain.CodePoints;
import com.moneylog.core.note.domain.Frontmatter;
import com.moneylog.core.note.domain.Frontmatter.LinkRef;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteContent;
import com.moneylog.core.note.domain.NoteLimits;
import com.moneylog.core.note.domain.NoteLink;
import com.moneylog.core.note.domain.NoteLinkType;
import com.moneylog.core.note.domain.NotePath;
import com.moneylog.core.note.domain.WikilinkResolver;
import com.moneylog.core.note.exception.NoteExceptions.NoteFrontmatterInvalidException;
import com.moneylog.core.note.exception.NoteExceptions.NoteLimitReachedException;
import com.moneylog.core.note.exception.NoteExceptions.NotePathInvalidException;
import com.moneylog.core.note.exception.NoteExceptions.NoteWikilinkLimitReachedException;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.text.PlainText;

public class ImportVaultUseCaseImpl implements ImportVaultUseCase {

    private final NoteGateway notes;
    private final NoteTargetGateway targets;
    private final IdGenerator ids;

    public ImportVaultUseCaseImpl(NoteGateway notes, NoteTargetGateway targets, IdGenerator ids) {
        this.notes = notes;
        this.targets = targets;
        this.ids = ids;
    }

    static final int REPORT_TEXT_MAX = 200;

    @Override
    public ImportReport handle(ImportVaultCommand c) {
        try (VaultZipReader.Archive archive = VaultZipReader.open(c.zip())) {
            Scan scan = archive.scan();
            notes.lockVault(c.userId());
            Run run = new Run(c);
            for (IgnoredEntry ignored : scan.ignored()) {
                run.ignoredFiles.add(clip(ignored.name()));
                if (ignored.reason() == IgnoreReason.NOTE_TOO_LARGE) {
                    run.warn(ignored.name(), "NOTE_TOO_LARGE", "A nota passa de " + NoteLimits.NOTE_MAX_BYTES / 1024 + " KB e foi ignorada.");
                } else if (ignored.reason() == IgnoreReason.PATH_UNSUPPORTED) {
                    run.warn(ignored.name(), "NOTE_PATH_INVALID", "O nome do arquivo não é aceito pelo vault e a nota foi ignorada.");
                }
                if (ignored.nameGuessed() && ignored.reason() != IgnoreReason.NOT_MARKDOWN) {
                    run.warn(ignored.name(), "NAME_ENCODING_GUESSED", ENCODING_MESSAGE);
                }
            }
            archive.readNotes(run::importNote);
            run.resolveDeferredNoteLinks();
            VaultIndex.refreshWikilinks(notes, c.userId(), run.createdPaths, run.createdLinkKeys);
            int pending = run.createdIds.isEmpty() ? 0 : notes.countPendingLinks(c.userId(), run.createdIds);
            return new ImportReport(run.createdIds.size(), run.skippedFiles.size(), pending, run.ignoredFiles, run.skippedFiles, run.renamed,
                    run.warnings);
        }
    }

    private static final String ENCODING_MESSAGE = "O zip não declara UTF-8: o nome do arquivo foi lido como CP437 e pode estar com acentos trocados.";

    private static String clip(String text) {
        return CodePoints.ellipsize(text, REPORT_TEXT_MAX);
    }

    private final class Run {
        final ImportVaultCommand command;
        final Map<String, UUID> idByKey = new HashMap<>();
        final int existing;
        final List<UUID> createdIds = new ArrayList<>();
        final List<NotePath> createdPaths = new ArrayList<>();
        final Set<String> createdLinkKeys = new java.util.HashSet<>();
        final List<String> ignoredFiles = new ArrayList<>();
        final List<String> skippedFiles = new ArrayList<>();
        final List<RenamedFile> renamed = new ArrayList<>();
        final List<ImportWarning> warnings = new ArrayList<>();
        final Map<UUID, List<LinkRef>> deferredNoteLinks = new LinkedHashMap<>();
        final Map<UUID, List<NoteLink>> knownLinks = new HashMap<>();

        Run(ImportVaultCommand command) {
            this.command = command;
            for (WikilinkResolver.Entry entry : notes.activeIndex(command.userId())) {
                idByKey.put(entry.path().key(), entry.id());
            }
            this.existing = notes.countAll(command.userId());
        }

        void warn(String path, String code, String message) {
            warnings.add(new ImportWarning(clip(path), code, clip(message)));
        }

        void importNote(NoteEntry entry) {
            NotePath path = entry.path();
            RenamedFile rename = null;
            if (idByKey.containsKey(path.key())) {
                if (command.mode() == ConflictMode.SKIP) {
                    skippedFiles.add(clip(entry.name()));
                    return;
                }
                NotePath free = nextFree(path);
                if (free == null) {
                    skippedFiles.add(clip(entry.name()));
                    warn(entry.name(), "NOTE_PATH_TAKEN", "Não foi possível escolher um nome livre; a nota foi pulada.");
                    return;
                }
                rename = new RenamedFile(clip(path.value()), clip(free.value()));
                path = free;
            }
            String text = decode(entry);
            if (text == null) {
                return;
            }
            List<NoteLink> explicit = new ArrayList<>();
            List<LinkRef> deferred = new ArrayList<>();
            try {
                NoteContent.validateText(text);
                text = prepareFrontmatter(entry.name(), text, explicit, deferred);
                if (existing + createdIds.size() >= NoteLimits.MAX_NOTES) {
                    throw new NoteLimitReachedException(NoteLimits.MAX_NOTES);
                }
                UUID id = ids.next();
                Note note = Note.create(id, command.userId(), path, text, explicit, new WikilinkResolver(List.of()));
                notes.save(note);
                idByKey.put(path.key(), id);
                createdIds.add(id);
                createdPaths.add(path);
                note.wikilinks().forEach(l -> createdLinkKeys.add(WikilinkResolver.nameKey(l.text())));
                if (rename != null) {
                    renamed.add(rename);
                    warn(entry.name(), "NOTE_RENAMED", "Já existia uma nota neste caminho: a importada ficou como '" + rename.to()
                            + "'. Importar o mesmo zip de novo com RENAME cria outra cópia; use SKIP para não duplicar.");
                }
                if (entry.nameGuessed()) {
                    warn(entry.name(), "NAME_ENCODING_GUESSED", ENCODING_MESSAGE);
                }
                if (!deferred.isEmpty()) {
                    deferredNoteLinks.put(id, deferred);
                    knownLinks.put(id, explicit);
                }
            } catch (NoteLimitReachedException e) {
                throw e;
            } catch (NoteWikilinkLimitReachedException e) {
                ignoredFiles.add(clip(entry.name()));
                warn(entry.name(), "NOTE_TOO_MANY_LINKS", "A nota tem mais de " + NoteLimits.MAX_WIKILINKS + " wikilinks distintos e foi ignorada.");
            } catch (BusinessRuleException e) {
                ignoredFiles.add(clip(entry.name()));
                warn(entry.name(), e.code(), "A nota foi ignorada: " + e.code() + ".");
            }
        }

        private String prepareFrontmatter(String name, String text, List<NoteLink> explicit, List<LinkRef> deferred) {
            Frontmatter.Parsed parsed = Frontmatter.parse(text);
            if (!parsed.present() || !parsed.hasMoneylogKey()) {
                return text;
            }
            List<LinkRef> refs = List.of();
            try {
                refs = parsed.moneylogLinks();
            } catch (NoteFrontmatterInvalidException e) {
                warn(name, "FRONTMATTER_INVALID", "Os vínculos de 'moneylog' estão mal formados e foram descartados.");
            }
            explicit.addAll(toLinks(name, refs, deferred));
            String stripped = Frontmatter.stripMoneylog(text);
            if (Frontmatter.parse(stripped).hasMoneylogKey()) {
                warn(name, "FRONTMATTER_INVALID", "A chave 'moneylog' tem uma forma que não pôde ser removida: o frontmatter foi descartado.");
                return Frontmatter.dropBlock(text);
            }
            return stripped;
        }

        private List<NoteLink> toLinks(String name, List<LinkRef> allRefs, List<LinkRef> deferred) {
            List<LinkRef> refs = new ArrayList<>();
            for (LinkRef ref : allRefs) {
                if (PlainText.isSafeSingleLine(ref.type()) && PlainText.isSafeSingleLine(ref.id()) && PlainText.isSafeSingleLine(ref.key())
                        && PlainText.isSafeSingleLine(ref.path())) {
                    refs.add(ref);
                } else {
                    warn(name, "LINK_IGNORED", "Um vínculo com caractere de controle ou de formatação foi ignorado.");
                }
            }
            List<NoteLink> links = new ArrayList<>();
            Set<TargetRef> lookups = new java.util.LinkedHashSet<>();
            Map<LinkRef, UUID> parsedIds = new HashMap<>();
            for (LinkRef ref : refs) {
                NoteLinkType type = typeOf(ref.type());
                if (type == null) {
                    warn(name, "LINK_IGNORED", "Vínculo com tipo desconhecido '" + CodePoints.ellipsize(ref.type(), 40) + "' ignorado.");
                } else if (type == NoteLinkType.NOTE || type.usesKey()) {
                    continue;
                } else if (ref.id() != null) {
                    UUID id = parseUuid(ref.id());
                    if (id != null) {
                        parsedIds.put(ref, id);
                        lookups.add(new TargetRef(type, id));
                    }
                }
            }
            Set<TargetRef> found = lookups.isEmpty() ? Set.of() : targets.describe(command.userId(), lookups).keySet();
            for (LinkRef ref : refs) {
                NoteLinkType type = typeOf(ref.type());
                if (type == null) {
                    continue;
                }
                try {
                    NoteLink link = toLink(type, ref, parsedIds.get(ref), found, deferred);
                    if (link != null) {
                        links.add(link);
                    }
                } catch (BusinessRuleException e) {
                    warn(name, "LINK_IGNORED", "Vínculo " + type + " ignorado: " + e.code() + ".");
                }
            }
            return links;
        }

        private NoteLink toLink(NoteLinkType type, LinkRef ref, UUID id, Set<TargetRef> found, List<LinkRef> deferred) {
            if (type == NoteLinkType.NOTE) {
                if (ref.path() == null) {
                    throw new com.moneylog.core.note.exception.NoteExceptions.NoteLinkInvalidException("NOTE precisa de 'path'.");
                }
                deferred.add(ref);
                return null;
            }
            if (type.usesKey()) {
                if (ref.key() == null) {
                    throw new com.moneylog.core.note.exception.NoteExceptions.NoteLinkInvalidException("MONTHLY_SUMMARY precisa de 'key'.");
                }
                return NoteLink.isValidMonth(ref.key()) ? NoteLink.explicitMonth(ref.key()) : NoteLink.pending(type, ref.key());
            }
            if (ref.id() == null) {
                throw new com.moneylog.core.note.exception.NoteExceptions.NoteLinkInvalidException(type + " precisa de 'id'.");
            }
            if (id != null && found.contains(new TargetRef(type, id))) {
                return NoteLink.explicit(type, id);
            }
            return NoteLink.pending(type, ref.id());
        }

        void resolveDeferredNoteLinks() {
            for (Map.Entry<UUID, List<LinkRef>> entry : deferredNoteLinks.entrySet()) {
                List<NoteLink> links = new ArrayList<>(knownLinks.get(entry.getKey()));
                for (LinkRef ref : entry.getValue()) {
                    UUID target = idByKey.get(pathKey(ref.path()));
                    links.add(target != null && !target.equals(entry.getKey()) ? NoteLink.explicit(NoteLinkType.NOTE, target)
                            : NoteLink.pending(NoteLinkType.NOTE, CodePoints.truncate(ref.path(), 255)));
                }
                Versioned<Note> stored = notes.findById(command.userId(), entry.getKey()).orElseThrow();
                stored.value().replaceExplicitLinks(links);
                notes.save(stored.value());
            }
        }

        private String pathKey(String raw) {
            try {
                return NotePath.of(raw).key();
            } catch (NotePathInvalidException e) {
                return "\u0000" + raw;
            }
        }

        private NotePath nextFree(NotePath path) {
            for (int n = 2; n < 10_000; n++) {
                try {
                    NotePath candidate = path.withName(path.name() + " (" + n + ")");
                    if (!idByKey.containsKey(candidate.key())) {
                        return candidate;
                    }
                } catch (NotePathInvalidException e) {
                    return null;
                }
            }
            return null;
        }

        private String decode(NoteEntry entry) {
            try {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(entry.data())).toString();
                return text.startsWith("﻿") ? text.substring(1) : text;
            } catch (CharacterCodingException e) {
                ignoredFiles.add(clip(entry.name()));
                warn(entry.name(), "NOTE_INVALID_ENCODING", "O arquivo não é texto UTF-8 e foi ignorado.");
                return null;
            }
        }
    }

    private static NoteLinkType typeOf(String raw) {
        try {
            return raw == null ? null : NoteLinkType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
