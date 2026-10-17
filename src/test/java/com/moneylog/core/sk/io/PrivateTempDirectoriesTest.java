package com.moneylog.core.sk.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PrivateTempDirectoriesTest {

    @Test
    @DisplayName("a pasta temporária nasce só com rwx do dono")
    void deveCriarPastaSoDoDono() throws IOException {
        Path dir = PrivateTempDirectories.create("moneylog-test-");
        try {
            assertThat(dir).isDirectory();
            assertThat(dir.getFileName().toString()).startsWith("moneylog-test-");
            assertThat(Files.getPosixFilePermissions(dir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        } finally {
            Files.deleteIfExists(dir);
        }
    }
}
