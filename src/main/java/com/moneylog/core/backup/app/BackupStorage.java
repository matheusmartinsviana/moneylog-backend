package com.moneylog.core.backup.app;

import java.nio.file.Path;
import java.util.List;

/**
 * Porta do armazenamento de backups (T176). Implementações: diretório local (padrão) e S3. As chaves são caminhos com
 * {@code /} (ex.: {@code moneylog/2026/10/07/moneylog-20261007T030000Z.dump.enc}); {@code list} devolve só o que está sob o prefixo.
 */
public interface BackupStorage {

    void put(String key, Path file);

    /** Baixa o objeto para {@code target} (sobrescreve). {@link BackupException} se não existir. */
    void get(String key, Path target);

    List<StoredBackup> list(String prefix);

    /** Idempotente: apagar o que não existe não falha. */
    void delete(String key);
}
