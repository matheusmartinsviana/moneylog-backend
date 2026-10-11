package com.moneylog.core.note.app.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.moneylog.core.note.domain.Frontmatter;
import com.moneylog.core.note.domain.Frontmatter.LinkRef;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteLink;
import com.moneylog.core.note.domain.NoteLinkType;

public final class VaultZipWriter {

    private VaultZipWriter() {
    }

    public static int write(ZipOutputStream zip, Note note, Instant modified, Map<UUID, String> pathById, Map<UUID, String> trashedPathById)
            throws IOException {
        ZipEntry entry = new ZipEntry(note.path().value());
        entry.setLastModifiedTime(FileTime.from(modified));
        zip.putNextEntry(entry);
        byte[] data = textOf(note, pathById, trashedPathById).getBytes(StandardCharsets.UTF_8);
        zip.write(data);
        zip.closeEntry();
        return data.length;
    }

    public static String textOf(Note note, Map<UUID, String> pathById, Map<UUID, String> trashedPathById) {
        return Frontmatter.withMoneylogLinks(note.body(), refsOf(note, pathById, trashedPathById));
    }

    static List<LinkRef> refsOf(Note note, Map<UUID, String> pathById, Map<UUID, String> trashedPathById) {
        List<LinkRef> refs = new ArrayList<>();
        for (NoteLink link : note.explicitLinks()) {
            String type = link.type().name();
            if (link.type() == NoteLinkType.NOTE) {
                if (link.targetId() != null) {
                    String path = pathById.get(link.targetId());
                    if (path != null) {
                        refs.add(new LinkRef(type, null, null, path));
                    } else if (trashedPathById.containsKey(link.targetId())) {
                        refs.add(new LinkRef(type, null, null, trashedPathById.get(link.targetId()), "trashed"));
                    }
                } else {
                    refs.add(new LinkRef(type, null, null, link.text()));
                }
            } else if (link.targetId() != null) {
                refs.add(new LinkRef(type, link.targetId().toString(), null, null));
            } else if (link.targetKey() != null) {
                refs.add(new LinkRef(type, null, link.targetKey(), null));
            } else if (link.type().usesKey()) {
                refs.add(new LinkRef(type, null, link.text(), null));
            } else {
                refs.add(new LinkRef(type, link.text(), null, null));
            }
        }
        return refs;
    }
}
