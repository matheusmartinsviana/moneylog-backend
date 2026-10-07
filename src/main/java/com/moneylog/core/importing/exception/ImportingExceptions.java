package com.moneylog.core.importing.exception;

import com.moneylog.core.importing.domain.ImportLineStatus;
import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.exception.NotFoundException;

/**
 * Exceções da importação de extrato.
 */
public final class ImportingExceptions {

    private ImportingExceptions() {
    }

    public static class InvalidImportLineException extends BusinessRuleException {
        public InvalidImportLineException(String reason) {
            super("IMPORT_LINE_INVALID", "Linha de importação inválida: " + reason);
        }
    }

    /** Ação não permitida no status atual da linha em revisão. */
    public static class InvalidImportLineTransitionException extends BusinessRuleException {
        public InvalidImportLineTransitionException(ImportLineStatus from, String action) {
            super("IMPORT_LINE_INVALID_TRANSITION", "Não é possível '" + action + "' uma linha " + from + ".");
        }
    }

    /** Arquivo acima de 5 MB ou 5.000 linhas (S-171). */
    public static class ImportTooLargeException extends BusinessRuleException {
        public ImportTooLargeException(String reason) {
            super("IMPORT_TOO_LARGE", "Arquivo grande demais: " + reason);
        }
    }

    public static class ImportFormatUnsupportedException extends BusinessRuleException {
        public ImportFormatUnsupportedException(String reason) {
            super("IMPORT_FORMAT_UNSUPPORTED", "Formato não suportado: " + reason);
        }
    }

    /** O arquivo não pôde ser lido; {@code line} aponta a linha do arquivo (0 = arquivo inteiro). */
    public static class ImportParseFailedException extends BusinessRuleException {
        private final int line;

        public ImportParseFailedException(int line, String reason) {
            super("IMPORT_PARSE_FAILED", line > 0 ? "Linha " + line + " do arquivo: " + reason : "Arquivo ilegível: " + reason);
            this.line = line;
        }

        public int line() {
            return line;
        }
    }

    public static class ImportMappingInvalidException extends BusinessRuleException {
        public ImportMappingInvalidException(String reason) {
            super("IMPORT_MAPPING_INVALID", "Mapeamento de colunas inválido: " + reason);
        }
    }

    public static class ImportBatchNotFoundException extends NotFoundException {
        public ImportBatchNotFoundException(UUID id) {
            super("IMPORT_BATCH_NOT_FOUND", "Lote de importação não encontrado: " + id);
        }
    }

    public static class ImportLineNotFoundException extends NotFoundException {
        public ImportLineNotFoundException(UUID id) {
            super("IMPORT_LINE_NOT_FOUND", "Linha de importação não encontrada: " + id);
        }
    }

    public static class ImportBatchNotPendingException extends BusinessRuleException {
        public ImportBatchNotPendingException(UUID id, String status) {
            super("IMPORT_BATCH_NOT_PENDING", "O lote " + id + " está " + status + " e não aceita esta ação.");
        }
    }

    public static class ImportBatchNotUndoableException extends BusinessRuleException {
        public ImportBatchNotUndoableException(UUID id, String status) {
            super("IMPORT_BATCH_NOT_UNDOABLE", "O lote " + id + " está " + status + " e não pode ser desfeito.");
        }
    }

    public static class ImportLinesRequireDecisionException extends BusinessRuleException {
        public ImportLinesRequireDecisionException(int count) {
            super("IMPORT_LINES_REQUIRE_DECISION", count + " linha(s) pedem decisão (transferência, parcela ou possível duplicata) antes de aplicar.");
        }
    }

    public static class ImportNothingToApplyException extends BusinessRuleException {
        public ImportNothingToApplyException() {
            super("IMPORT_NOTHING_TO_APPLY", "O lote não tem nenhuma linha aceita.");
        }
    }

    /** Fonte (conta ou cartão) do lote: exatamente uma. */
    public static class ImportSourceRequiredException extends BusinessRuleException {
        public ImportSourceRequiredException() {
            super("IMPORT_SOURCE_INVALID", "Informe a conta ou o cartão do extrato (apenas um).");
        }
    }

    public static class ImportRuleNotFoundException extends NotFoundException {
        public ImportRuleNotFoundException(UUID id) {
            super("CATEGORIZATION_RULE_NOT_FOUND", "Regra de categorização não encontrada: " + id);
        }
    }

    public static class ImportSourceNotFoundException extends NotFoundException {
        public ImportSourceNotFoundException(boolean card, UUID id) {
            super(card ? "CARD_NOT_FOUND" : "ACCOUNT_NOT_FOUND", (card ? "Cartão" : "Conta") + " não encontrado(a): " + id);
        }
    }

    public static class ImportIdempotencyKeyReusedException extends ConflictException {
        public ImportIdempotencyKeyReusedException() {
            super("IDEMPOTENCY_KEY_REUSED", "A Idempotency-Key já foi usada com outro conteúdo.");
        }
    }

    /** Uma linha não pôde virar movimentação; nada do lote foi gravado (rollback total) e ele segue em revisão. */
    public static class ImportApplyFailedException extends BusinessRuleException {
        private final int position;
        private final String causeCode;

        public ImportApplyFailedException(int position, String causeCode, String causeMessage) {
            super("IMPORT_APPLY_FAILED", "Linha " + position + " do lote não pôde ser aplicada (" + causeCode + "): " + causeMessage);
            this.position = position;
            this.causeCode = causeCode;
        }

        public int position() {
            return position;
        }

        public String causeCode() {
            return causeCode;
        }
    }

    public static class ImportMappingNotFoundException extends NotFoundException {
        public ImportMappingNotFoundException(UUID sourceId) {
            super("IMPORT_MAPPING_NOT_FOUND", "Ainda não há mapeamento de colunas salvo para " + sourceId + ".");
        }
    }

    /** A perna oposta da transferência já é movimentação ativa: transferir de novo contaria o valor duas vezes (PR 01). */
    public static class ImportTransferLegAlreadyRecordedException extends BusinessRuleException {
        public ImportTransferLegAlreadyRecordedException() {
            super("IMPORT_TRANSFER_LEG_ALREADY_RECORDED", "A outra ponta desta transferência já está registrada como entrada ou saída em outra conta. "
                    + "Transferir de novo contaria o valor duas vezes: aceite a linha como está ou rejeite.");
        }
    }

    /** Já existe movimentação ativa com a mesma chave (o mesmo extrato aplicado em outro lote). */
    public static class ImportLineAlreadyRecordedException extends ConflictException {
        public ImportLineAlreadyRecordedException() {
            super("IMPORT_LINE_ALREADY_RECORDED", "Esta linha já foi importada em outro lote. Rejeite-a ou descarte o lote.");
        }
    }
}
