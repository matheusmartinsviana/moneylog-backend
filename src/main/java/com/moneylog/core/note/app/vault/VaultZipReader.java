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

/**
 * Leitura <b>defensiva</b> do zip importado (S-162, S-272, ADR-009). O upload é copiado para um arquivo temporário privado (diretório
 * {@code rwx------}, arquivo {@code rw-------}, nomes aleatórios, apagado sempre em {@link Archive#close()}) e aberto com
 * {@link ZipFile}, que lê o <b>diretório central</b>: zip truncado (inclusive cortado na fronteira de uma entrada) ou sem diretório
 * central é recusado inteiro, nunca importado pela metade. Nada é extraído: cada entrada é lida em fluxo, contada e descartada ou
 * entregue em memória (só {@code .md} de até 200 KB). Recusa o zip inteiro, sem gravar nada:
 * <ul>
 *   <li>arquivo acima de 20 MB, mais de 5.500 entradas ou mais de 300 MB descomprimidos (os bytes são contados ao ler, nunca os tamanhos
 *       declarados, que mentem em zip bomb): {@code VAULT_IMPORT_TOO_LARGE};</li>
 *   <li>nome com {@code ..}, {@code .}, caminho absoluto ({@code /x}, {@code C:/x}), {@code \} ou caractere de controle (zip slip), em
 *       <b>qualquer</b> entrada; zip corrompido, truncado, CRC ou tamanho que não batem, ou que não é zip: {@code VAULT_IMPORT_INVALID}.</li>
 * </ul>
 * O resto não derruba a importação: arquivo que não é {@code .md}, pasta oculta, nome sem suporte (reservado do Windows, caractere proibido
 * como {@code :}) e nota acima de 200 KB são <b>ignorados</b> e listados. Nomes sem a flag UTF-8 são lidos como UTF-8 e, se isso falhar,
 * como CP437 (o relatório avisa {@code NAME_ENCODING_GUESSED}).
 */
public final class VaultZipReader {

    /** Por que um arquivo foi ignorado. */
    public enum IgnoreReason { NOT_MARKDOWN, HIDDEN, PATH_UNSUPPORTED, NOTE_TOO_LARGE }

    /** Uma entrada {@code .md} importável. {@code data} só vem preenchido quando o chamador pediu o conteúdo. */
    public record NoteEntry(int index, String name, NotePath path, byte[] data, boolean nameGuessed) {
    }

    public record IgnoredEntry(String name, IgnoreReason reason, boolean nameGuessed) {
    }

    /** Resultado da primeira passada: o que será importado e o que será ignorado. */
    public record Scan(List<NoteEntry> notes, List<IgnoredEntry> ignored, int entryCount, boolean namesGuessed) {
    }

    /** Recebe cada nota na segunda passada (uma por vez, para não manter o zip todo em memória). */
    @FunctionalInterface
    public interface NoteHandler {
        void accept(NoteEntry entry);
    }

    private static final int DRAIN_BUFFER = 16 * 1024;
    private static final Charset CP437 = Charset.forName("IBM437");

    private VaultZipReader() {
    }

    /** Atalho: abre, faz a primeira passada e fecha. */
    public static Scan scan(ZipSource source) {
        try (Archive archive = open(source)) {
            return archive.scan();
        }
    }

    /** Atalho: abre e entrega o conteúdo de cada nota importável. */
    public static void readNotes(ZipSource source, NoteHandler handler) {
        try (Archive archive = open(source)) {
            archive.readNotes(handler);
        }
    }

    /**
     * Copia o upload para um arquivo temporário seguro (contando os bytes lidos) e abre o zip pelo diretório central.
     *
     * @throws VaultImportTooLargeException mais de 20 MB (contados ao copiar)
     * @throws VaultImportInvalidException  não é zip, está truncado ou sem diretório central
     */
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
            // sistema sem POSIX: o diretório temporário já é do processo
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

    /** O zip aberto (diretório central lido) sobre o arquivo temporário; {@link #close()} apaga tudo. */
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
                    // nome fora de UTF-8 sem a flag EFS (zips antigos do Windows): tenta CP437
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

        /** Primeira passada: valida o zip inteiro sem reter conteúdo. */
        public Scan scan() {
            List<NoteEntry> notes = new ArrayList<>();
            List<IgnoredEntry> ignored = new ArrayList<>();
            int count = walk(false, notes::add, ignored::add);
            return new Scan(notes, ignored, count, namesGuessed);
        }

        /** Segunda passada: entrega o conteúdo de cada nota importável, na mesma ordem do {@link #scan}. */
        public void readNotes(NoteHandler handler) {
            walk(true, handler::accept, ignored -> { });
        }

        @Override
        public void close() {
            try {
                zip.close();
            } catch (IOException ignored) {
                // o arquivo é apagado de qualquer forma
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

    /** Hostil = aborta (em {@link #requireSafeName}); sem suporte = ignora. Só {@code .md} válido vira nota. */
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

    /** Zip slip: recusa nome que escapa da pasta, em qualquer entrada (inclusive as que seriam ignoradas). */
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
        // "C:/x" é caminho absoluto; "Q: dúvida.md" é só um nome com dois-pontos e é ignorado (não derruba o zip)
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

    /**
     * Lê a entrada inteira contando os bytes; guarda até {@code keep} e para quando o orçamento total estoura. Confere o CRC e o tamanho
     * declarados no diretório central (o {@link ZipFile}, ao contrário do {@code ZipInputStream}, não confere o CRC sozinho).
     */
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
