package com.moneylog.core.backup.app;

import java.nio.file.Path;
import java.util.List;

public interface BackupStorage {

    void put(String key, Path file);

    void get(String key, Path target);

    List<StoredBackup> list(String prefix);

    void delete(String key);
}
