package com.moneylog.core.asset.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.NotFoundException;

public final class AssetExceptions {

    private AssetExceptions() {
    }

    public static class AssetNotFoundException extends NotFoundException {
        public AssetNotFoundException(UUID id) {
            super("ASSET_NOT_FOUND", "Ativo não encontrado: " + id + ".");
        }
    }

    public static class AssetInvalidException extends BusinessRuleException {
        public AssetInvalidException(String reason) {
            super("ASSET_INVALID", "Ativo inválido: " + reason);
        }
    }

    public static class AssetArchivedException extends BusinessRuleException {
        public AssetArchivedException() {
            super("ASSET_ARCHIVED", "O ativo está arquivado.");
        }
    }
}
