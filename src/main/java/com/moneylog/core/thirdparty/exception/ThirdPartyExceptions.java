package com.moneylog.core.thirdparty.exception;

import com.moneylog.core.sk.exception.BusinessRuleException;

public final class ThirdPartyExceptions {

    private ThirdPartyExceptions() {
    }

    public static class ThirdPartyExpenseInvalidException extends BusinessRuleException {
        public ThirdPartyExpenseInvalidException(String reason) {
            super("THIRD_PARTY_EXPENSE_INVALID", "Despesa de terceiro inválida: " + reason);
        }
    }

    public static class IdempotencyKeyRequiredException extends com.moneylog.core.sk.exception.DomainException {
        public IdempotencyKeyRequiredException() {
            super("IDEMPOTENCY_KEY_REQUIRED", "O cabeçalho Idempotency-Key é obrigatório neste recebimento (sem conta).");
        }
    }

    public static class ReceiptNotRemovableException extends BusinessRuleException {
        public ReceiptNotRemovableException(String reason) {
            super("THIRD_PARTY_RECEIPT_NOT_REMOVABLE", "O recebimento não pode ser removido: " + reason);
        }
    }

    public static class ThirdPartyExpenseNotFoundException extends com.moneylog.core.sk.exception.NotFoundException {
        public ThirdPartyExpenseNotFoundException(java.util.UUID id) {
            super("THIRD_PARTY_EXPENSE_NOT_FOUND", "Marca de despesa de terceiro não encontrada: " + id + ".");
        }
    }

    public static class ThirdPartyExpenseAlreadyMarkedException extends BusinessRuleException {
        public ThirdPartyExpenseAlreadyMarkedException() {
            super("THIRD_PARTY_EXPENSE_ALREADY_MARKED", "Esta despesa já está marcada como de terceiro.");
        }
    }

    public static class ShareMigrationReportMismatchException extends com.moneylog.core.sk.exception.ConflictException {
        public ShareMigrationReportMismatchException() {
            super("SHARE_MIGRATION_REPORT_MISMATCH", "O relatório aprovado não corresponde mais aos dados atuais. Gere o dry-run de novo.");
        }
    }

    public static class ShareMigrationBackupRequiredException extends BusinessRuleException {
        public ShareMigrationBackupRequiredException() {
            super("SHARE_MIGRATION_BACKUP_REQUIRED", "Faça e confirme o backup antes de aplicar a migração.");
        }
    }

    public static class ShareMigrationNothingToApplyException extends BusinessRuleException {
        public ShareMigrationNothingToApplyException() {
            super("SHARE_MIGRATION_NOTHING_TO_APPLY", "Não há marca elegível para migrar.");
        }
    }

    public static class ReceivableNotFoundException extends com.moneylog.core.sk.exception.NotFoundException {
        public ReceivableNotFoundException() {
            super("RECEIVABLE_NOT_FOUND", "Recebível não encontrado.");
        }
    }

    public static class ReceivableAlreadyReceivedException extends BusinessRuleException {
        public ReceivableAlreadyReceivedException() {
            super("RECEIVABLE_ALREADY_RECEIVED", "Este recebível já foi recebido por inteiro; o mês previsto não pode mais ser alterado.");
        }
    }

    public static class ReceivableExpectedMonthInvalidException extends BusinessRuleException {
        public ReceivableExpectedMonthInvalidException() {
            super("RECEIVABLE_EXPECTED_MONTH_INVALID", "O mês previsto precisa estar entre 2000-01 e 2100-12.");
        }
    }
}
