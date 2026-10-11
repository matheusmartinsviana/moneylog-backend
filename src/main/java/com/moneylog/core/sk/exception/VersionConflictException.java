package com.moneylog.core.sk.exception;

public class VersionConflictException extends ConflictException {

    public VersionConflictException(long sent, long current) {
        super("VERSION_CONFLICT", "Versão desatualizada: enviada " + sent + ", atual " + current + ".");
    }

    public VersionConflictException() {
        super("VERSION_CONFLICT", "O registro foi alterado por outra requisição. Recarregue e tente de novo.");
    }
}
