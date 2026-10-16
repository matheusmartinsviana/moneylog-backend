package com.moneylog.core.purchasegroup.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.NotFoundException;

public final class PurchaseGroupExceptions {

    private PurchaseGroupExceptions() {
    }

    public static class PurchaseGroupNotFoundException extends NotFoundException {
        public PurchaseGroupNotFoundException(UUID id) {
            super("PURCHASE_GROUP_NOT_FOUND", "Grupo de compra não encontrado: " + id + ".");
        }
    }

    public static class PurchaseGroupInvalidException extends BusinessRuleException {
        public PurchaseGroupInvalidException(String reason) {
            super("PURCHASE_GROUP_INVALID", "Grupo de compra inválido: " + reason);
        }
    }

    public static class PurchaseGroupMemberInvalidException extends BusinessRuleException {
        public PurchaseGroupMemberInvalidException(String reason) {
            super("PURCHASE_GROUP_MEMBER_INVALID", "Compra inválida para o grupo: " + reason);
        }
    }
}
