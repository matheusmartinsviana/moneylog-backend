package com.moneylog.core.auth.exception;

public class OwnerEmailConflictException extends IllegalStateException {

    public OwnerEmailConflictException() {
        super("MONEYLOG_OWNER_EMAIL diverge do e-mail do dono gravado no banco: a subida foi interrompida para não renomear nem "
                + "promover ninguém em silêncio. Restaure o MONEYLOG_OWNER_EMAIL anterior ou troque o e-mail do dono por operação manual "
                + "deliberada (docs/operacao/deploy-gratis-aws.md).");
    }
}
