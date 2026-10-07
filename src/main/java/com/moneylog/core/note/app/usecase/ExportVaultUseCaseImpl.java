package com.moneylog.core.note.app.usecase;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipOutputStream;

import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.app.vault.VaultZipWriter;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteLimits;
import com.moneylog.core.note.domain.WikilinkResolver;
import com.moneylog.core.note.exception.NoteExceptions.VaultExportTooLargeException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class ExportVaultUseCaseImpl implements ExportVaultUseCase {

    static final int BATCH = 50;

    private final NoteGateway notes;
    private final TimeProvider time;

    public ExportVaultUseCaseImpl(NoteGateway notes, TimeProvider time) {
        this.notes = notes;
        this.time = time;
    }

    @Override
    public VaultExport handle(ExportVaultQuery q) {
        List<WikilinkResolver.Entry> index = notes.activeIndex(q.userId());
        if (index.size() > NoteLimits.IMPORT_MAX_ENTRIES) {
            throw new VaultExportTooLargeException("mais de " + NoteLimits.IMPORT_MAX_ENTRIES + " notas.");
        }
        Map<UUID, String> pathById = new HashMap<>();
        index.forEach(entry -> pathById.put(entry.id(), entry.path().value()));
        Map<UUID, String> trashed = notes.trashedPaths(q.userId());
        String day = time.now().atZone(time.zone()).toLocalDate().toString();
        Path dir = null;
        Path file = null;
        try {
            dir = privateDirectory();
            file = Files.createTempFile(dir, "export-", ".zip");
            build(q.userId(), file, pathById, trashed);
            long size = Files.size(file);
            return new TempFileExport("moneylog-vault-" + day + ".zip", index.size(), size, dir, file);
        } catch (IOException e) {
            delete(file);
            delete(dir);
            throw new java.io.UncheckedIOException("falha ao montar o zip da exportação", e);
        } catch (RuntimeException e) {
            delete(file);
            delete(dir);
            throw e;
        }
    }

    /** Monta o zip em lotes, parando assim que passa de um limite da importação. */
    private void build(UUID userId, Path file, Map<UUID, String> pathById, Map<UUID, String> trashed) throws IOException {
        try (CountingOutputStream counted = new CountingOutputStream(Files.newOutputStream(file)); ZipOutputStream zip = new ZipOutputStream(counted)) {
            long uncompressed = 0;
            UUID after = null;
            while (true) {
                List<Versioned<Note>> batch = notes.findActiveBatch(userId, after, BATCH);
                for (Versioned<Note> versioned : batch) {
                    uncompressed += VaultZipWriter.write(zip, versioned.value(), versioned.updatedAt(), pathById, trashed);
                    if (uncompressed > NoteLimits.IMPORT_MAX_UNCOMPRESSED_BYTES) {
                        throw new VaultExportTooLargeException("mais de " + NoteLimits.IMPORT_MAX_UNCOMPRESSED_BYTES / (1024 * 1024) + " MB de texto.");
                    }
                    if (counted.count > NoteLimits.IMPORT_MAX_ZIP_BYTES) {
                        throw new VaultExportTooLargeException("o zip passaria de " + NoteLimits.IMPORT_MAX_ZIP_BYTES / (1024 * 1024) + " MB.");
                    }
                }
                if (batch.size() < BATCH) {
                    break;
                }
                after = batch.get(batch.size() - 1).value().id();
            }
            zip.finish();
        }
        if (Files.size(file) > NoteLimits.IMPORT_MAX_ZIP_BYTES) {
            throw new VaultExportTooLargeException("o zip passaria de " + NoteLimits.IMPORT_MAX_ZIP_BYTES / (1024 * 1024) + " MB.");
        }
    }

    private static Path privateDirectory() throws IOException {
        try {
            return Files.createTempDirectory("moneylog-export-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException notPosix) {
            return Files.createTempDirectory("moneylog-export-");
        }
    }

    private static void delete(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                path.toFile().deleteOnExit();
            }
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    private static final class TempFileExport implements VaultExport {
        private final String fileName;
        private final int noteCount;
        private final long size;
        private final Path dir;
        private final Path file;

        TempFileExport(String fileName, int noteCount, long size, Path dir, Path file) {
            this.fileName = fileName;
            this.noteCount = noteCount;
            this.size = size;
            this.dir = dir;
            this.file = file;
        }

        @Override
        public String fileName() {
            return fileName;
        }

        @Override
        public int noteCount() {
            return noteCount;
        }

        @Override
        public long sizeBytes() {
            return size;
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            Files.copy(file, out);
        }

        @Override
        public void close() {
            delete(file);
            delete(dir);
        }
    }
}
