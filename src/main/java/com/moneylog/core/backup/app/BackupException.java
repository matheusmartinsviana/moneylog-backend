package com.moneylog.core.backup.app;

/** Falha de backup/restauração. A mensagem nunca leva chave, senha nem conteúdo do dump. */
public class BackupException extends RuntimeException {

    public BackupException(String message) {
        super(message);
    }

    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
