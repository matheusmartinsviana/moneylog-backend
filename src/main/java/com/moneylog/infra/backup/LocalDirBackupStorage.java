package com.moneylog.infra.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.moneylog.core.backup.app.BackupException;
import com.moneylog.core.backup.app.BackupStorage;
import com.moneylog.core.backup.app.StoredBackup;

public class LocalDirBackupStorage implements BackupStorage {

    private final Path root;

    public LocalDirBackupStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, Path file) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BackupException("falha ao gravar o backup no diretório local", e);
        }
    }

    @Override
    public void get(String key, Path target) {
        Path source = resolve(key);
        if (!Files.isRegularFile(source)) {
            throw new BackupException("backup não encontrado: " + key);
        }
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BackupException("falha ao ler o backup do diretório local", e);
        }
    }

    @Override
    public List<StoredBackup> list(String prefix) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(k -> k.startsWith(prefix))
                    .map(k -> {
                        try {
                            Path p = resolve(k);
                            return new StoredBackup(k, Files.getLastModifiedTime(p).toInstant(), Files.size(p));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            throw new BackupException("falha ao listar os backups do diretório local", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new BackupException("falha ao apagar o backup do diretório local", e);
        }
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            throw new BackupException("chave de backup inválida");
        }
        return p;
    }
}
