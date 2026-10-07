package com.moneylog.infra.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.moneylog.core.backup.app.BackupException;

@DisplayName("Armazenamento local de backup")
class LocalDirBackupStorageTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("grava, lista, baixa e remove; remover o que não existe não falha")
    void deveFazerCicloCompleto() throws Exception {
        LocalDirBackupStorage s = new LocalDirBackupStorage(dir.resolve("root"));
        Path src = dir.resolve("src");
        Files.writeString(src, "abc");

        s.put("moneylog/2026/10/07/a.dump", src);

        assertThat(s.list("moneylog/")).singleElement().satisfies(b -> {
            assertThat(b.key()).isEqualTo("moneylog/2026/10/07/a.dump");
            assertThat(b.sizeBytes()).isEqualTo(3);
        });
        assertThat(s.list("outro/")).isEmpty();
        Path out = dir.resolve("out");
        s.get("moneylog/2026/10/07/a.dump", out);
        assertThat(Files.readString(out)).isEqualTo("abc");
        s.delete("moneylog/2026/10/07/a.dump");
        s.delete("moneylog/2026/10/07/a.dump");
        assertThat(s.list("moneylog/")).isEmpty();
    }

    @Test
    @DisplayName("chave que escapa da raiz é recusada")
    void deveRecusarPathTraversal() throws Exception {
        LocalDirBackupStorage s = new LocalDirBackupStorage(dir.resolve("root"));
        Path src = dir.resolve("src");
        Files.writeString(src, "x");
        assertThatThrownBy(() -> s.put("../fora.dump", src)).isInstanceOf(BackupException.class);
        assertThatThrownBy(() -> s.get("../src", dir.resolve("o"))).isInstanceOf(BackupException.class);
    }
}
