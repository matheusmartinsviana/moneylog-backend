package com.moneylog.core.backup.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifra opcional do backup (T176 CA4): AES-256-GCM com chave derivada da {@code BACKUP_ENCRYPTION_KEY} por PBKDF2.
 * Formato: {@code "MLBK1" | salt(16) | iv(12) | cifra+tag}. O GCM autentica: chave errada ou arquivo adulterado falha na
 * verificação do final do arquivo, e quem chama só aplica o resultado depois de descriptografar tudo para um arquivo temporário.
 */
public final class BackupCrypto {

    private static final byte[] MAGIC = "MLBK1".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT = 16;
    private static final int IV = 12;
    private static final int ITERATIONS = 210_000;
    private static final int MIN_KEY_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final char[] key;

    public BackupCrypto(String key) {
        if (key == null || key.strip().length() < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException("BACKUP_ENCRYPTION_KEY precisa ter pelo menos " + MIN_KEY_LENGTH + " caracteres.");
        }
        this.key = key.strip().toCharArray();
    }

    public void encrypt(Path plain, Path encrypted) {
        byte[] salt = new byte[SALT];
        byte[] iv = new byte[IV];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(iv);
        try (InputStream in = Files.newInputStream(plain); OutputStream raw = Files.newOutputStream(encrypted)) {
            raw.write(MAGIC);
            raw.write(salt);
            raw.write(iv);
            try (OutputStream out = new CipherOutputStream(raw, cipher(Cipher.ENCRYPT_MODE, salt, iv))) {
                in.transferTo(out);
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new BackupException("falha ao cifrar o backup", e);
        }
    }

    /** Lança {@link BackupException} com chave errada ou arquivo adulterado; apaga o arquivo parcial. */
    public void decrypt(Path encrypted, Path plain) {
        try (InputStream raw = Files.newInputStream(encrypted)) {
            byte[] magic = raw.readNBytes(MAGIC.length);
            byte[] salt = raw.readNBytes(SALT);
            byte[] iv = raw.readNBytes(IV);
            if (!Arrays.equals(magic, MAGIC) || salt.length != SALT || iv.length != IV) {
                throw new BackupException("arquivo de backup não é um backup cifrado do moneylog");
            }
            try (InputStream in = new CipherInputStream(raw, cipher(Cipher.DECRYPT_MODE, salt, iv));
                 OutputStream out = Files.newOutputStream(plain)) {
                in.transferTo(out);
            }
        } catch (IOException | GeneralSecurityException e) {
            deleteQuietly(plain);
            throw new BackupException("não foi possível descriptografar o backup (chave errada ou arquivo corrompido)");
        } catch (BackupException e) {
            deleteQuietly(plain);
            throw e;
        }
    }

    public static boolean looksEncrypted(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.equals(in.readNBytes(MAGIC.length), MAGIC);
        } catch (IOException e) {
            return false;
        }
    }

    private Cipher cipher(int mode, byte[] salt, byte[] iv) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(key, salt, ITERATIONS, 256);
        try {
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(derived, "AES"), new GCMParameterSpec(128, iv));
            return cipher;
        } finally {
            spec.clearPassword();
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // melhor esforço
        }
    }
}
