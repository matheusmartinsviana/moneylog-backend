package com.moneylog.core.backup.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.moneylog.infra.backup.LocalDirBackupStorage;

@DisplayName("Serviço de backup")
class BackupServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    /** Dumper de teste: "dump" é um texto fixo; guarda o que restaurou. */
    static class FakeDumper implements DatabaseDumper {
        String restored;

        @Override
        public void dump(Path target) {
            try {
                Files.writeString(target, "DUMP-CONTEUDO");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void restore(Path source) {
            try {
                restored = Files.readString(source);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    @DisplayName("grava com prefixo por data, aplica retenção só no prefixo do app e restaura")
    void deveFazerBackupERestaurar() throws Exception {
        LocalDirBackupStorage storage = new LocalDirBackupStorage(dir);
        Path outro = dir.resolve("outro-app/2020/01/01/x.dump");
        Files.createDirectories(outro.getParent());
        Files.writeString(outro, "nao mexer");
        for (int d = 1; d <= 8; d++) {
            Path old = dir.resolve("moneylog/2026/09/%02d/velho.dump".formatted(d));
            Files.createDirectories(old.getParent());
            Files.writeString(old, "velho");
            Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(Instant.parse("2026-09-%02dT03:00:00Z".formatted(d))));
        }
        FakeDumper dumper = new FakeDumper();
        BackupService service = new BackupService(dumper, storage, Optional.empty(), "moneylog", 7, CLOCK);

        String key = service.backup();

        assertThat(key).isEqualTo("moneylog/2026/10/07/moneylog-20261007T030000Z.dump");
        assertThat(storage.list("moneylog/")).hasSize(7);
        assertThat(dir.resolve("moneylog/2026/09/01/velho.dump")).doesNotExist();
        assertThat(dir.resolve("moneylog/2026/09/02/velho.dump")).doesNotExist();
        assertThat(outro).exists();
        service.restore(key);
        assertThat(dumper.restored).isEqualTo("DUMP-CONTEUDO");
    }

    @Test
    @DisplayName("com chave: o armazenado é cifrado, a restauração devolve o dump; chave errada não chama o pg_restore")
    void deveCifrarERestaurarComChave() throws Exception {
        LocalDirBackupStorage storage = new LocalDirBackupStorage(dir);
        FakeDumper dumper = new FakeDumper();
        BackupService service = new BackupService(dumper, storage, Optional.of(new BackupCrypto("chave-de-teste-nao-e-segredo-123")), "moneylog/", 7, CLOCK);

        String key = service.backup();

        assertThat(key).endsWith(".dump.enc");
        assertThat(Files.readString(dir.resolve(key), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("DUMP-CONTEUDO");
        service.restore(key);
        assertThat(dumper.restored).isEqualTo("DUMP-CONTEUDO");

        DatabaseDumper strict = mock(DatabaseDumper.class);
        BackupService wrong = new BackupService(strict, storage, Optional.of(new BackupCrypto("outra-chave-bem-diferente-999")), "moneylog/", 7, CLOCK);
        assertThatThrownBy(() -> wrong.restore(key)).isInstanceOf(BackupException.class);
        verify(strict, never()).restore(any());
    }

    @Test
    @DisplayName("restaurar um backup cifrado sem chave configurada falha antes do pg_restore")
    void deveFalharSemChave() {
        LocalDirBackupStorage storage = new LocalDirBackupStorage(dir);
        String key = new BackupService(new FakeDumper(), storage, Optional.of(new BackupCrypto("chave-de-teste-nao-e-segredo-123")), "moneylog/", 7, CLOCK).backup();
        DatabaseDumper strict = mock(DatabaseDumper.class);

        assertThatThrownBy(() -> new BackupService(strict, storage, Optional.empty(), "moneylog/", 7, CLOCK).restore(key))
                .isInstanceOf(BackupException.class);
        verify(strict, never()).restore(any());
    }

    @Test
    @DisplayName("chave fora do prefixo do app é recusada")
    void deveRecusarChaveForaDoPrefixo() {
        BackupService service = new BackupService(new FakeDumper(), new LocalDirBackupStorage(dir), Optional.empty(), "moneylog/", 7, CLOCK);
        assertThatThrownBy(() -> service.restore("outro-app/x.dump")).isInstanceOf(BackupException.class);
    }
}
