package com.moneylog.core.backup.app;

import java.nio.file.Path;

/** Porta do dump do banco (formato custom do {@code pg_dump}). A implementação de produção chama {@code pg_dump}/{@code pg_restore}. */
public interface DatabaseDumper {

    /** Escreve o dump em {@code target}. */
    void dump(Path target);

    /** Restaura o dump no banco de destino (que deve estar vazio). */
    void restore(Path source);
}
