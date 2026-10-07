package com.moneylog.core.backup.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Cifra do backup (AES-256-GCM)")
class BackupCryptoTest {

    private static final String KEY = "chave-de-teste-nao-e-segredo-123";

    @TempDir
    Path dir;

    @Test
    @DisplayName("o arquivo cifrado não é legível sem a chave e a chave certa restaura o conteúdo idêntico")
    void deveCifrarEDescriptografar() throws Exception {
        Path plain = dir.resolve("plain");
        byte[] content = "PGDMP saldo=12345 conta=Nubank".getBytes(StandardCharsets.UTF_8);
        Files.write(plain, content);
        Path enc = dir.resolve("enc");
        Path back = dir.resolve("back");

        new BackupCrypto(KEY).encrypt(plain, enc);
        new BackupCrypto(KEY).decrypt(enc, back);

        assertThat(new String(Files.readAllBytes(enc), StandardCharsets.ISO_8859_1)).doesNotContain("saldo", "Nubank");
        assertThat(BackupCrypto.looksEncrypted(enc)).isTrue();
        assertThat(BackupCrypto.looksEncrypted(plain)).isFalse();
        assertThat(Files.readAllBytes(back)).isEqualTo(content);
    }

    @Test
    @DisplayName("chave errada falha sem deixar arquivo parcial")
    void deveFalharComChaveErrada() throws Exception {
        Path plain = dir.resolve("plain");
        Files.writeString(plain, "conteudo");
        Path enc = dir.resolve("enc");
        new BackupCrypto(KEY).encrypt(plain, enc);
        Path back = dir.resolve("back");

        assertThatThrownBy(() -> new BackupCrypto("outra-chave-bem-diferente-999").decrypt(enc, back))
                .isInstanceOf(BackupException.class).hasMessageNotContaining("outra-chave");
        assertThat(back).doesNotExist();
    }

    @Test
    @DisplayName("arquivo adulterado falha na verificação do GCM")
    void deveFalharComArquivoAdulterado() throws Exception {
        Path plain = dir.resolve("plain");
        Files.writeString(plain, "conteudo importante");
        Path enc = dir.resolve("enc");
        new BackupCrypto(KEY).encrypt(plain, enc);
        byte[] bytes = Files.readAllBytes(enc);
        bytes[bytes.length - 3] ^= 0x01;
        Files.write(enc, bytes);

        assertThatThrownBy(() -> new BackupCrypto(KEY).decrypt(enc, dir.resolve("back"))).isInstanceOf(BackupException.class);
    }

    @Test
    @DisplayName("chave curta é recusada")
    void deveRecusarChaveCurta() {
        assertThatThrownBy(() -> new BackupCrypto("curta")).isInstanceOf(IllegalArgumentException.class);
    }
}
