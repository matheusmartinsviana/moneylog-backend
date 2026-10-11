package com.moneylog.core.note.app;

import static com.moneylog.core.note.util.NoteTestKit.source;
import static com.moneylog.core.note.util.NoteTestKit.sourceWithSize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.note.app.vault.VaultZipReader;
import com.moneylog.core.note.app.vault.VaultZipReader.IgnoreReason;
import com.moneylog.core.note.app.vault.VaultZipReader.NoteEntry;
import com.moneylog.core.note.app.vault.VaultZipReader.Scan;
import com.moneylog.core.note.domain.NoteLimits;
import com.moneylog.core.note.exception.NoteExceptions.VaultImportInvalidException;
import com.moneylog.core.note.exception.NoteExceptions.VaultImportTooLargeException;
import com.moneylog.core.note.util.NoteTestKit.Zip;

@DisplayName("VaultZipReader (T112, S-162): leitura defensiva do zip (zip slip, zip bomb, nomes, limites)")
class VaultZipReaderTest {

    @Test
    @DisplayName("lista as notas .md (com pastas) e ignora o que não é nota: anexos, pastas ocultas, __MACOSX; diretórios nem aparecem")
    void deveSepararNotasEIgnorados() {
        Scan scan = VaultZipReader.scan(new Zip().dir("reviews").add("reviews/2026-10.md", "a").add("MacBook.md", "b").add("foto.png", new byte[] {1, 2})
                .add(".obsidian/app.json", "{}").add("__MACOSX/._MacBook.md", "x").add(".trash/velha.md", "x").add("LEIA.txt", "oi").source());
        assertThat(scan.notes()).extracting(e -> e.path().value()).containsExactly("reviews/2026-10.md", "MacBook.md");
        assertThat(scan.ignored()).extracting(VaultZipReader.IgnoredEntry::name).containsExactly("foto.png", ".obsidian/app.json", "__MACOSX/._MacBook.md",
                ".trash/velha.md", "LEIA.txt");
        assertThat(scan.ignored()).extracting(VaultZipReader.IgnoredEntry::reason).containsExactly(IgnoreReason.NOT_MARKDOWN, IgnoreReason.HIDDEN,
                IgnoreReason.HIDDEN, IgnoreReason.HIDDEN, IgnoreReason.NOT_MARKDOWN);
        assertThat(scan.entryCount()).isEqualTo(8);
        assertThat(scan.notes().get(0).data()).isNull();
    }

    @Test
    @DisplayName("a segunda passada entrega o conteúdo de cada nota, na ordem")
    void deveEntregarOConteudo() {
        List<String> seen = new ArrayList<>();
        VaultZipReader.readNotes(new Zip().add("a.md", "um").add("foto.png", "x").add("b/c.md", "dois").source(),
                (NoteEntry entry) -> seen.add(entry.path() + "=" + new String(entry.data(), java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(seen).containsExactly("a.md=um", "b/c.md=dois");
    }

    @ParameterizedTest(name = "[{index}] zip slip: \"{0}\" recusa o zip inteiro")
    @ValueSource(strings = {"../../etc/passwd.md", "../x.md", "a/../../x.md", "/etc/passwd.md", "C:/x.md", "a\\b.md", "..\\x.md", "x\u0000.md", "x\u001b.md",
            "./x.md", "a/./x.md", "../evil.sh", "/abs/foto.png", "a\\b.png", "pasta/../../x"})
    void deveRecusarCaminhoHostil(String name) {
        byte[] zip = new Zip().add("ok.md", "x").add(name, "mal").bytes();
        assertThatThrownBy(() -> VaultZipReader.scan(source(zip))).isInstanceOf(VaultImportInvalidException.class).extracting("code")
                .isEqualTo("VAULT_IMPORT_INVALID");
    }

    @Test
    @DisplayName("CA4 (S-272): mais de 5.500 entradas (5.000 notas + acessórios) é VAULT_IMPORT_TOO_LARGE; exatamente 5.500 passa")
    void deveLimitarAsEntradas() {
        Zip ok = new Zip();
        for (int i = 0; i < NoteLimits.IMPORT_MAX_ENTRIES; i++) {
            ok.add("n" + i + ".md", "");
        }
        assertThat(VaultZipReader.scan(ok.source()).notes()).hasSize(NoteLimits.IMPORT_MAX_ENTRIES);
        Zip many = new Zip();
        for (int i = 0; i <= NoteLimits.IMPORT_MAX_ENTRIES; i++) {
            many.add("n" + i + ".md", "");
        }
        byte[] bytes = many.bytes();
        assertThatThrownBy(() -> VaultZipReader.scan(source(bytes))).isInstanceOf(VaultImportTooLargeException.class).extracting("code")
                .isEqualTo("VAULT_IMPORT_TOO_LARGE");
    }

    @Test
    @DisplayName("CA4: arquivo acima de 20 MB é VAULT_IMPORT_TOO_LARGE antes de ler qualquer byte")
    void deveLimitarOTamanhoEnviado() {
        byte[] bytes = new Zip().add("a.md", "x").bytes();
        assertThat(VaultZipReader.scan(sourceWithSize(bytes, NoteLimits.IMPORT_MAX_ZIP_BYTES)).notes()).hasSize(1);
        assertThatThrownBy(() -> VaultZipReader.scan(sourceWithSize(bytes, NoteLimits.IMPORT_MAX_ZIP_BYTES + 1))).isInstanceOf(VaultImportTooLargeException.class);
    }

    @Test
    @DisplayName("CA4: zip bomb: descompressão acima de 300 MB é recusada contando os bytes lidos (um zip de poucos KB)")
    void deveRecusarZipBomb() throws IOException {
        byte[] bomb = zeros(301);
        assertThat(bomb.length).isLessThan(1024 * 1024);
        assertThatThrownBy(() -> VaultZipReader.scan(source(bomb))).isInstanceOf(VaultImportTooLargeException.class);
        assertThatThrownBy(() -> VaultZipReader.readNotes(source(bomb), e -> { })).isInstanceOf(VaultImportTooLargeException.class);
    }

    @Test
    @DisplayName("CA4: vários arquivos que somam mais de 300 MB também são recusados; abaixo do limite passa")
    void deveSomarOsArquivos() throws IOException {
        byte[] ok = zeros(150, 100);
        assertThat(VaultZipReader.scan(source(ok)).ignored()).hasSize(2);
        assertThatThrownBy(() -> VaultZipReader.scan(source(zeros(150, 151)))).isInstanceOf(VaultImportTooLargeException.class);
    }

    private static byte[] zeros(int... megabytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            byte[] block = new byte[1024 * 1024];
            int n = 0;
            for (int mb : megabytes) {
                zip.putNextEntry(new ZipEntry("dados" + n++ + ".bin"));
                for (int i = 0; i < mb; i++) {
                    zip.write(block);
                }
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    @DisplayName("nota acima de 200 KB é ignorada (NOTE_TOO_LARGE) sem derrubar a importação; exatamente 200 KB entra")
    void deveIgnorarNotaGrande() {
        Scan scan = VaultZipReader.scan(new Zip().add("exata.md", "a".repeat(NoteLimits.NOTE_MAX_BYTES)).add("grande.md", "a".repeat(NoteLimits.NOTE_MAX_BYTES + 1))
                .add("pequena.md", "x").source());
        assertThat(scan.notes()).extracting(e -> e.path().value()).containsExactly("exata.md", "pequena.md");
        assertThat(scan.ignored()).extracting(VaultZipReader.IgnoredEntry::name, VaultZipReader.IgnoredEntry::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("grande.md", IgnoreReason.NOTE_TOO_LARGE));
    }

    @Test
    @DisplayName("nome sem suporte (reservado do Windows, caractere proibido, maiúscula na extensão ok) é ignorado, não hostil")
    void deveIgnorarNomeSemSuporte() {
        Scan scan = VaultZipReader.scan(new Zip().add("con.md", "x").add("a<b>.md", "x").add("pasta/Nota.MD", "x").source());
        assertThat(scan.notes()).extracting(e -> e.path().value()).containsExactly("pasta/Nota.md");
        assertThat(scan.ignored()).extracting(VaultZipReader.IgnoredEntry::reason).containsOnly(IgnoreReason.PATH_UNSUPPORTED);
    }

    @Test
    @DisplayName("não é zip, vazio, truncado ou corrompido: VAULT_IMPORT_INVALID")
    void deveRecusarOQueNaoEZip() {
        assertThatThrownBy(() -> VaultZipReader.scan(source("isto não é um zip".getBytes()))).isInstanceOf(VaultImportInvalidException.class);
        assertThatThrownBy(() -> VaultZipReader.scan(source(new byte[0]))).isInstanceOf(VaultImportInvalidException.class);
        assertThatThrownBy(() -> VaultZipReader.scan(source(new byte[] {'P', 'K', 3}))).isInstanceOf(VaultImportInvalidException.class);
        byte[] good = new Zip().add("a.md", "x".repeat(5000)).add("b.md", "y").bytes();
        byte[] truncated = java.util.Arrays.copyOf(good, good.length / 2);
        assertThatThrownBy(() -> VaultZipReader.scan(source(truncated))).isInstanceOf(VaultImportInvalidException.class);
        byte[] corrupted = good.clone();
        for (int i = 40; i < 80; i++) {
            corrupted[i] = (byte) 0xFF;
        }
        assertThatThrownBy(() -> VaultZipReader.scan(source(corrupted))).isInstanceOf(VaultImportInvalidException.class);
    }

    @Test
    @DisplayName("um zip vazio (só o fim do diretório) é válido e não traz nenhuma nota")
    void deveAceitarZipVazio() {
        Scan scan = VaultZipReader.scan(new Zip().source());
        assertThat(scan.notes()).isEmpty();
        assertThat(scan.entryCount()).isZero();
    }

    @Test
    @DisplayName("BUG-QA-02: zip truncado em qualquer ponto (inclusive na fronteira de uma entrada) é recusado inteiro; só o zip completo é lido")
    void deveRecusarZipTruncado() {
        Zip zip = new Zip();
        for (int i = 0; i < 12; i++) {
            zip.add("pasta/nota-" + i + ".md", "# Nota " + i + "\n" + "enchimento ".repeat(20));
        }
        byte[] full = zip.bytes();
        assertThat(VaultZipReader.scan(source(full)).notes()).hasSize(12);
        for (int cut = 1; cut < full.length; cut++) {
            byte[] truncated = java.util.Arrays.copyOf(full, cut);
            assertThatThrownBy(() -> VaultZipReader.scan(source(truncated))).as("corte em %d de %d bytes", cut, full.length)
                    .isInstanceOf(VaultImportInvalidException.class).extracting("code").isEqualTo("VAULT_IMPORT_INVALID");
        }
    }

    @Test
    @DisplayName("zip sem diretório central (só cabeçalhos locais) é recusado: não dá para saber se está completo")
    void deveRecusarZipSemDiretorioCentral() {
        byte[] full = new Zip().add("a.md", "um").add("b.md", "dois").bytes();
        int cen = indexOf(full, new byte[] {'P', 'K', 1, 2});
        byte[] noDirectory = java.util.Arrays.copyOf(full, cen);
        assertThatThrownBy(() -> VaultZipReader.scan(source(noDirectory))).isInstanceOf(VaultImportInvalidException.class);
    }

    @Test
    @DisplayName("CRC ou tamanho que não conferem com o diretório central: zip corrompido, recusado inteiro")
    void deveConferirOCrc() {
        byte[] stored = storedZip("a.md", "conteudo armazenado");
        byte[] flipped = stored.clone();
        flipped[30 + "a.md".length() + 3] ^= 0x20;
        assertThat(VaultZipReader.scan(source(stored)).notes()).hasSize(1);
        assertThatThrownBy(() -> VaultZipReader.scan(source(flipped))).isInstanceOf(VaultImportInvalidException.class);
    }

    @Test
    @DisplayName("BUG-QA-04: nome em CP1252/CP437 sem a flag UTF-8 é lido como CP437 e marcado (NAME_ENCODING_GUESSED); com a flag, UTF-8 normal")
    void deveAdivinharNomeSemFlagUtf8() {
        byte[] legacy = rawZip(new byte[] {'c', 'a', 'f', (byte) 0x82, '.', 'm', 'd'}, "corpo", false);
        Scan scan = VaultZipReader.scan(source(legacy));
        assertThat(scan.namesGuessed()).isTrue();
        assertThat(scan.notes()).extracting(e -> e.path().value(), NoteEntry::nameGuessed).containsExactly(org.assertj.core.groups.Tuple.tuple("café.md", true));
        Scan utf8 = VaultZipReader.scan(new Zip().add("café.md", "x").add("ok.md", "y").source());
        assertThat(utf8.namesGuessed()).isFalse();
        assertThat(utf8.notes()).extracting(e -> e.path().value()).containsExactly("café.md", "ok.md");
    }

    @Test
    @DisplayName("BUG-QA-05: letra + dois-pontos ('Q: dúvida.md') ignora só aquele arquivo; 'C:/x.md' continua abortando o zip")
    void deveIgnorarNomeComDoisPontos() {
        Scan scan = VaultZipReader.scan(new Zip().add("ok.md", "x").add("Q: dúvida.md", "x").add("a:b.md", "x").source());
        assertThat(scan.notes()).extracting(e -> e.path().value()).containsExactly("ok.md");
        assertThat(scan.ignored()).extracting(VaultZipReader.IgnoredEntry::name, VaultZipReader.IgnoredEntry::reason).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Q: dúvida.md", IgnoreReason.PATH_UNSUPPORTED), org.assertj.core.groups.Tuple.tuple("a:b.md", IgnoreReason.PATH_UNSUPPORTED));
        assertThatThrownBy(() -> VaultZipReader.scan(new Zip().add("ok.md", "x").add("C:/x.md", "x").source())).isInstanceOf(VaultImportInvalidException.class);
    }

    @Test
    @DisplayName("o arquivo temporário é privado (rwx------ / rw-------) e é apagado sempre: no fim, em recusa e em erro do chamador")
    void deveApagarOArquivoTemporario() throws IOException {
        java.nio.file.Path tmp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        long before = importDirs(tmp);
        try (VaultZipReader.Archive archive = VaultZipReader.open(new Zip().add("a.md", "x").source())) {
            java.nio.file.Path dir;
            try (var stream = java.nio.file.Files.list(tmp)) {
                dir = stream.filter(p -> p.getFileName().toString().startsWith("moneylog-import-")).findFirst().orElseThrow();
            }
            if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                assertThat(java.nio.file.Files.getPosixFilePermissions(dir)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                try (var files = java.nio.file.Files.list(dir)) {
                    java.nio.file.Path file = files.findFirst().orElseThrow();
                    assertThat(java.nio.file.Files.getPosixFilePermissions(file)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                }
            }
            assertThat(archive.scan().notes()).hasSize(1);
        }
        assertThat(importDirs(tmp)).isEqualTo(before);
        assertThatThrownBy(() -> VaultZipReader.scan(new Zip().add("../x.md", "x").source())).isInstanceOf(VaultImportInvalidException.class);
        assertThatThrownBy(() -> VaultZipReader.scan(source(new byte[] {'P', 'K', 3, 4}))).isInstanceOf(VaultImportInvalidException.class);
        assertThatThrownBy(() -> VaultZipReader.scan(source(zeros(301)))).isInstanceOf(VaultImportTooLargeException.class);
        assertThat(importDirs(tmp)).isEqualTo(before);
    }

    @Test
    @DisplayName("o limite de 20 MB também vale pelos bytes realmente copiados, não só pelo tamanho informado")
    void deveContarOsBytesCopiados() {
        byte[] bytes = new byte[(int) NoteLimits.IMPORT_MAX_ZIP_BYTES + 10];
        assertThatThrownBy(() -> VaultZipReader.scan(sourceWithSize(bytes, 100))).isInstanceOf(VaultImportTooLargeException.class);
    }

    private static long importDirs(java.nio.file.Path tmp) throws IOException {
        try (var stream = java.nio.file.Files.list(tmp)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("moneylog-import-")).count();
        }
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] storedZip(String name, String content) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ZipEntry entry = new ZipEntry(name);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(data);
                entry.setCrc(crc.getValue());
                zip.putNextEntry(entry);
                zip.write(data);
                zip.closeEntry();
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] rawZip(byte[] name, String content, boolean utf8Flag) {
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(1024).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        short flags = (short) (utf8Flag ? 0x0800 : 0);
        out.putInt(0x04034b50).putShort((short) 10).putShort(flags).putShort((short) 0).putShort((short) 0).putShort((short) 0x21)
                .putInt((int) crc.getValue()).putInt(data.length).putInt(data.length).putShort((short) name.length).putShort((short) 0).put(name).put(data);
        int centralStart = out.position();
        out.putInt(0x02014b50).putShort((short) 20).putShort((short) 10).putShort(flags).putShort((short) 0).putShort((short) 0).putShort((short) 0x21)
                .putInt((int) crc.getValue()).putInt(data.length).putInt(data.length).putShort((short) name.length).putShort((short) 0).putShort((short) 0)
                .putShort((short) 0).putShort((short) 0).putInt(0).putInt(0).put(name);
        int centralSize = out.position() - centralStart;
        out.putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) 1).putShort((short) 1).putInt(centralSize).putInt(centralStart)
                .putShort((short) 0);
        return java.util.Arrays.copyOf(out.array(), out.position());
    }
}
