package com.moneylog.core.backup.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public class BackupService {

    private static final DateTimeFormatter DAY_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final DatabaseDumper dumper;
    private final BackupStorage storage;
    private final Optional<BackupCrypto> crypto;
    private final String prefix;
    private final int retentionDays;
    private final Clock clock;

    public BackupService(DatabaseDumper dumper, BackupStorage storage, Optional<BackupCrypto> crypto, String prefix, int retentionDays,
                         Clock clock) {
        this.dumper = dumper;
        this.storage = storage;
        this.crypto = crypto;
        this.prefix = normalize(prefix);
        this.retentionDays = retentionDays;
        this.clock = clock;
    }

    public String backup() {
        Path work = tempDir();
        try {
            Path dump = work.resolve("dump");
            dumper.dump(dump);
            Path toStore = dump;
            String extension = ".dump";
            if (crypto.isPresent()) {
                toStore = work.resolve("dump.enc");
                crypto.get().encrypt(dump, toStore);
                extension = ".dump.enc";
            }
            String key = prefix + DAY_PATH.format(clock.instant()) + "/moneylog-" + STAMP.format(clock.instant()) + extension;
            storage.put(key, toStore);
            applyRetention();
            return key;
        } finally {
            deleteTree(work);
        }
    }

    public void restore(String key) {
        if (!key.startsWith(prefix)) {
            throw new BackupException("chave fora do prefixo de backup do app");
        }
        Path work = tempDir();
        try {
            Path downloaded = work.resolve("download");
            storage.get(key, downloaded);
            Path dump = downloaded;
            if (BackupCrypto.looksEncrypted(downloaded)) {
                BackupCrypto c = crypto.orElseThrow(() -> new BackupException("o backup é cifrado e BACKUP_ENCRYPTION_KEY não está definida"));
                dump = work.resolve("dump");
                c.decrypt(downloaded, dump);
            }
            dumper.restore(dump);
        } finally {
            deleteTree(work);
        }
    }

    public List<StoredBackup> list() {
        return storage.list(prefix).stream().sorted((a, b) -> b.lastModified().compareTo(a.lastModified())).toList();
    }

    public void applyRetention() {
        for (StoredBackup old : BackupRetention.toDelete(storage.list(prefix), retentionDays)) {
            storage.delete(old.key());
        }
    }

    private static String normalize(String prefix) {
        String p = prefix == null ? "" : prefix.strip();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p.isEmpty() || p.endsWith("/") ? p : p + "/";
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("moneylog-backup-");
        } catch (IOException e) {
            throw new BackupException("não foi possível criar a pasta temporária do backup", e);
        }
    }

    private static void deleteTree(Path dir) {
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }
}
