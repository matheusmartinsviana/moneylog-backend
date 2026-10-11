package com.moneylog.core.note.app.vault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import com.moneylog.core.note.domain.NoteLimits;
import com.moneylog.core.note.domain.NotePath;
import com.moneylog.core.note.exception.NoteExceptions.NotePathInvalidException;
import com.moneylog.core.note.exception.NoteExceptions.PathProblem;
import com.moneylog.core.note.exception.NoteExceptions.VaultImportInvalidException;
import com.moneylog.core.note.exception.NoteExceptions.VaultImportTooLargeException;

public final class VaultZipReader {

    public enum IgnoreReason { NOT_MARKDOWN, HIDDEN, PATH_UNSUPPORTED, NOTE_TOO_LARGE }

    public record NoteEntry(int index, String name, NotePath path, byte[] data, boolean nameGuessed) {
    }

    public record IgnoredEntry(String name, IgnoreReason reason, boolean nameGuessed) {
    }

    public record Scan(List<NoteEntry> notes, List<IgnoredEntry> ignored, int entryCount, boolean namesGuessed) {
    }

    @FunctionalInterface
    public interface NoteHandler {
        void accept(NoteEntry entry);
    }

    private static final int DRAIN_BUFFER = 16 * 1024;
    private static final Charset CP437 = Charset.forName("IBM437");

    private VaultZipReader() {
    }

    public static Scan scan(ZipSource source) {
        try (Archive archive = open(source)) {
            return archive.scan();
        }
    }

    public static void readNotes(ZipSource source, NoteHandler handler) {
        try (Archive archive = open(source)) {
            archive.readNotes(handler);
        }
    }

    public static Archive open(ZipSource source) {
        if (source.size() > NoteLimits.IMPORT_MAX_ZIP_BYTES) {
            throw new VaultImportTooLargeException("o arquivo passa de " + NoteLimits.IMPORT_MAX_ZIP_BYTES / (1024 * 1024) + " MB.");
        }
        Path dir = null;
        Path file = null;
        try {
            dir = createPrivateDirectory();
            file = Files.createTempFile(dir, "import-", ".zip");
            restrict(file, "rw-------");
            long copied = 0;
            try (InputStream in = source.open(); var out = Files.newOutputStream(file)) {
                byte[] buffer = new byte[DRAIN_BUFFER];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    copied += n;
                    if (copied > NoteLimits.IMPORT_MAX_ZIP_BYTES) {
                        throw new VaultImportTooLargeException("o arquivo passa de " + NoteLimits.IMPORT_MAX_ZIP_BYTES / (1024 * 1024) + " MB.");
                    }
                    out.write(buffer, 0, n);
                }
            }
            return new Archive(dir, file);
        } catch (RuntimeException | IOException e) {
            deleteQuietly(file);
            deleteQuietly(dir);
            if (e instanceof VaultImportTooLargeException || e instanceof VaultImportInvalidException) {
                throw (RuntimeException) e;
            }
            throw new VaultImportInvalidException("não foi possível ler o zip.");
        }
    }

    private static Path createPrivateDirectory() throws IOException {
        try {
            return Files.createTempDirectory("moneylog-import-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException notPosix) {
            return Files.createTempDirectory("moneylog-import-");
        }
    }

    private static void restrict(Path file, String permissions) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                path.toFile().deleteOnExit();
            }
        }
    }

    public static final class Archive implements AutoCloseable {
        private final Path dir;
        private final Path file;
        private final ZipFile zip;
        private final boolean namesGuessed;

        private Archive(Path dir, Path file) {
            this.dir = dir;
            this.file = file;
            boolean guessed = false;
            ZipFile opened;
            try {
                try {
                    opened = new ZipFile(file.toFile(), StandardCharsets.UTF_8);
                } catch (ZipException e) {
                    if (e.getMessage() == null || !e.getMessage().contains("entry name")) {
                        throw e;
                    }
                    opened = new ZipFile(file.toFile(), CP437);
                    guessed = true;
                }
            } catch (ZipException e) {
                cleanup();
                throw new VaultImportInvalidException(e.getMessage() != null && e.getMessage().contains("END header")
                        ? "o zip está truncado ou sem diretório central." : "o zip está corrompido ou não é um zip.");
            } catch (IOException | RuntimeException e) {
                cleanup();
                throw new VaultImportInvalidException("não foi possível ler o zip.");
            }
            this.zip = opened;
            this.namesGuessed = guessed;
        }

        public Scan scan() {
            List<NoteEntry> notes = new ArrayList<>();
            List<IgnoredEntry> ignored = new ArrayList<>();
            int count = walk(false, notes::add, ignored::add);
            return new Scan(notes, ignored, count, namesGuessed);
        }

        public void readNotes(NoteHandler handler) {
            walk(true, handler::accept, ignored -> { });
        }

        @Override
        public void close() {
            try {
                zip.close();
            } catch (IOException ignored) {
            }
            cleanup();
        }

        private void cleanup() {
            deleteQuietly(file);
            deleteQuietly(dir);
        }

        private int walk(boolean keepData, Consumer<NoteEntry> onNote, Consumer<IgnoredEntry> onIgnored) {
            if (zip.size() > NoteLimits.IMPORT_MAX_ENTRIES) {
                throw new VaultImportTooLargeException("mais de " + NoteLimits.IMPORT_MAX_ENTRIES + " entradas.");
            }
            long total = 0;
            int count = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            try {
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    count++;
                    String name = entry.getName();
                    requireSafeName(name);
                    if (entry.isDirectory()) {
                        continue;
                    }
                    Handling handling = classify(name);
                    int keep = handling.path() == null ? 0 : NoteLimits.NOTE_MAX_BYTES;
                    boolean guessed = namesGuessed && !isAscii(name);
                    Read read;
                    try (InputStream in = zip.getInputStream(entry)) {
                        if (in == null) {
                            throw new VaultImportInvalidException("entrada ilegível no zip.");
                        }
                        read = readBounded(in, entry, keep, NoteLimits.IMPORT_MAX_UNCOMPRESSED_BYTES - total);
                    }
                    total += read.total();
                    if (total > NoteLimits.IMPORT_MAX_UNCOMPRESSED_BYTES) {
                        throw new VaultImportTooLargeException("descompressão acima de " + NoteLimits.IMPORT_MAX_UNCOMPRESSED_BYTES / (1024 * 1024) + " MB.");
                    }
                    if (handling.path() == null) {
                        onIgnored.accept(new IgnoredEntry(name, handling.reason(), guessed));
                    } else if (read.overflow()) {
                        onIgnored.accept(new IgnoredEntry(name, IgnoreReason.NOTE_TOO_LARGE, guessed));
                    } else {
                        onNote.accept(new NoteEntry(count - 1, name, handling.path(), keepData ? read.data() : null, guessed));
                    }
                }
            } catch (ZipException e) {
                throw new VaultImportInvalidException("o zip está corrompido.");
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                throw new VaultImportInvalidException("não foi possível ler o zip.");
            }
            return count;
        }
    }

    private static boolean isAscii(String name) {
        return name.chars().allMatch(c -> c < 128);
    }

    private record Handling(NotePath path, IgnoreReason reason) {
    }

    private static Handling classify(String name) {
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(NotePath.EXTENSION)) {
            return new Handling(null, hiddenOrOther(name));
        }
        try {
            return new Handling(NotePath.of(name), null);
        } catch (NotePathInvalidException e) {
            if (e.problem() == PathProblem.HIDDEN_SEGMENT) {
                return new Handling(null, IgnoreReason.HIDDEN);
            }
            return new Handling(null, IgnoreReason.PATH_UNSUPPORTED);
        }
    }

    private static IgnoreReason hiddenOrOther(String name) {
        for (String segment : name.split("/")) {
            if (segment.startsWith(".") || segment.equals("__MACOSX")) {
                return IgnoreReason.HIDDEN;
            }
        }
        return IgnoreReason.NOT_MARKDOWN;
    }

    private static void requireSafeName(String name) {
        String check = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (check.isEmpty()) {
            throw new VaultImportInvalidException("entrada com nome vazio.");
        }
        for (int i = 0; i < check.length(); i++) {
            if (Character.isISOControl(check.charAt(i))) {
                throw new VaultImportInvalidException("nome de arquivo com caractere de controle.");
            }
        }
        if (check.indexOf('\\') >= 0) {
            throw new VaultImportInvalidException("nome de arquivo com '\\'.");
        }
        if (check.startsWith("/") || (check.length() >= 3 && check.charAt(1) == ':' && check.charAt(2) == '/' && Character.isLetter(check.charAt(0)))) {
            throw new VaultImportInvalidException("caminho absoluto no zip.");
        }
        for (String segment : check.split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new VaultImportInvalidException("caminho com '..' ou '.' no zip.");
            }
        }
    }

    private record Read(byte[] data, long total, boolean overflow) {
    }

    private static Read readBounded(InputStream in, ZipEntry entry, int keep, long budget) throws IOException {
        ByteArrayOutputStream kept = keep > 0 ? new ByteArrayOutputStream(Math.min(keep, 8 * 1024)) : null;
        byte[] buffer = new byte[DRAIN_BUFFER];
        CRC32 crc = new CRC32();
        long total = 0;
        boolean overflow = false;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > budget) {
                return new Read(null, total, true);
            }
            crc.update(buffer, 0, n);
            if (kept != null && !overflow) {
                if (kept.size() + n > keep) {
                    overflow = true;
                    kept = null;
                } else {
                    kept.write(buffer, 0, n);
                }
            }
        }
        if ((entry.getSize() >= 0 && entry.getSize() != total) || (entry.getCrc() >= 0 && entry.getCrc() != crc.getValue())) {
            throw new VaultImportInvalidException("o zip está corrompido (tamanho ou CRC não conferem).");
        }
        return new Read(kept == null ? null : kept.toByteArray(), total, overflow);
    }
}
