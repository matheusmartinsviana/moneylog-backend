package com.moneylog.core.finance.app.export;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;

public final class BackupExceptions {

    private BackupExceptions() {
    }

    public static class BackupInvalidException extends BusinessRuleException {

        public BackupInvalidException(String reason) {
            super("BACKUP_INVALID", "O backup não pôde ser lido: " + reason);
        }

        public BackupInvalidException(String reason, Throwable cause) {
            super("BACKUP_INVALID", "O backup não pôde ser lido: " + reason, cause);
        }
    }

    public static class BackupSchemaUnsupportedException extends BusinessRuleException {

        public BackupSchemaUnsupportedException(int version) {
            super("BACKUP_SCHEMA_UNSUPPORTED", "A versão " + version + " do backup não é suportada (aceitas: "
                    + BackupSnapshot.LEGACY_SCHEMA_VERSION + " e " + BackupSnapshot.SCHEMA_VERSION + ").");
        }
    }

    public static class RestoreTargetNotEmptyException extends ConflictException {

        public RestoreTargetNotEmptyException() {
            super("RESTORE_TARGET_NOT_EMPTY", "A restauração só roda em uma conta sem dados financeiros.");
        }
    }

    public static class RestoreConflictException extends ConflictException {

        public RestoreConflictException() {
            super("RESTORE_CONFLICT", "O backup conflita com dados já existentes e nada foi restaurado.");
        }
    }
}
