package com.moneylog.core.note.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.exception.NotFoundException;

public final class NoteExceptions {

    private NoteExceptions() {
    }

    public static class NoteNotFoundException extends NotFoundException {
        public NoteNotFoundException(UUID noteId) {
            super("NOTE_NOT_FOUND", "Nota não encontrada: " + noteId + ".");
        }
    }

    public enum PathProblem {
        EMPTY(false), TOO_LONG(false), TOO_DEEP(false), CONTROL_CHARACTERS(true), BACKSLASH(true), ABSOLUTE(true), TRAVERSAL(true),
        DRIVE_LETTER(true), EMPTY_SEGMENT(false), HIDDEN_SEGMENT(false), SEGMENT_WHITESPACE(false), FORBIDDEN_CHARACTER(false),
        RESERVED_NAME(false), EXTENSION(false);

        private final boolean hostile;

        PathProblem(boolean hostile) {
            this.hostile = hostile;
        }

        public boolean hostile() {
            return hostile;
        }
    }

    public static class NotePathInvalidException extends BusinessRuleException {
        private final PathProblem problem;

        public NotePathInvalidException(PathProblem problem, String detail) {
            super("NOTE_PATH_INVALID", "Caminho de nota inválido (" + problem + "): " + detail);
            this.problem = problem;
        }

        public PathProblem problem() {
            return problem;
        }
    }

    public static class NotePathTakenException extends BusinessRuleException {
        public NotePathTakenException(String path) {
            super("NOTE_PATH_TAKEN", "Já existe uma nota ativa com o caminho '" + path + "'.");
        }
    }

    public static class NoteTooLargeException extends BusinessRuleException {
        public NoteTooLargeException(int maxBytes) {
            super("NOTE_TOO_LARGE", "A nota pode ter no máximo " + maxBytes + " bytes.");
        }
    }

    public static class NoteLimitReachedException extends BusinessRuleException {
        public NoteLimitReachedException(int max) {
            super("NOTE_LIMIT_REACHED", "O vault pode ter no máximo " + max + " notas (a lixeira conta até ser esvaziada).");
        }
    }

    public static class NoteLinkTargetNotFoundException extends BusinessRuleException {
        public NoteLinkTargetNotFoundException(String type, String ref) {
            super("NOTE_LINK_TARGET_NOT_FOUND", "O alvo do vínculo não existe: " + type + " " + ref + ".");
        }
    }

    public static class NoteLinkInvalidException extends BusinessRuleException {
        public NoteLinkInvalidException(String reason) {
            super("NOTE_LINK_INVALID", "Vínculo inválido: " + reason);
        }
    }

    public static class NoteLinkLimitReachedException extends BusinessRuleException {
        public NoteLinkLimitReachedException(int max) {
            super("NOTE_LINK_LIMIT_REACHED", "Uma nota pode ter no máximo " + max + " vínculos explícitos.");
        }

        protected NoteLinkLimitReachedException(String message) {
            super("NOTE_LINK_LIMIT_REACHED", message);
        }
    }

    public static class NoteWikilinkLimitReachedException extends NoteLinkLimitReachedException {
        public NoteWikilinkLimitReachedException(int max) {
            super("Uma nota pode ter no máximo " + max + " wikilinks distintos no texto.");
        }
    }

    public static class NoteRewriteTooLargeException extends BusinessRuleException {
        private final String notePath;

        public NoteRewriteTooLargeException(String notePath, int maxBytes) {
            super("NOTE_TOO_LARGE", "Renomear reescreve links na nota '" + notePath + "', que passaria de " + maxBytes
                    + " bytes com o nome novo. Encurte o nome ou edite essa nota e tente de novo; nada foi alterado.");
            this.notePath = notePath;
        }

        public String notePath() {
            return notePath;
        }
    }

    public static class NoteTrashedException extends BusinessRuleException {
        public NoteTrashedException() {
            super("NOTE_TRASHED", "A nota está na lixeira. Restaure para editar.");
        }
    }

    public static class NoteAlreadyTrashedException extends BusinessRuleException {
        public NoteAlreadyTrashedException() {
            super("NOTE_ALREADY_TRASHED", "A nota já está na lixeira.");
        }
    }

    public static class NoteNotTrashedException extends BusinessRuleException {
        public NoteNotTrashedException() {
            super("NOTE_NOT_TRASHED", "A nota não está na lixeira.");
        }
    }

    public static class NoteFrontmatterInvalidException extends BusinessRuleException {
        public NoteFrontmatterInvalidException(String reason) {
            super("NOTE_FRONTMATTER_INVALID", "Frontmatter inválido: " + reason);
        }
    }

    public static class NoteFrontmatterReservedException extends BusinessRuleException {
        public NoteFrontmatterReservedException() {
            super("NOTE_FRONTMATTER_RESERVED", "A chave 'moneylog' do frontmatter é reservada: use os vínculos da nota.");
        }
    }

    public static class VaultImportInvalidException extends BusinessRuleException {
        public VaultImportInvalidException(String reason) {
            super("VAULT_IMPORT_INVALID", "Importação recusada: " + reason);
        }
    }

    public static class VaultImportTooLargeException extends BusinessRuleException {
        public VaultImportTooLargeException(String reason) {
            super("VAULT_IMPORT_TOO_LARGE", "Importação acima dos limites: " + reason);
        }
    }

    public static class VaultExportTooLargeException extends BusinessRuleException {
        public VaultExportTooLargeException(String reason) {
            super("EXPORT_TOO_LARGE", "O vault é grande demais para exportar em um zip que a importação aceite: " + reason);
        }
    }

    public static class NoteSearchTimeoutException extends BusinessRuleException {
        public NoteSearchTimeoutException() {
            super("NOTE_SEARCH_TIMEOUT", "A busca demorou demais. Use um termo mais específico ou filtre por pasta ou tag.");
        }
    }

    public static class NoteStateInconsistentException extends DomainException {
        public NoteStateInconsistentException(String reason) {
            super("NOTE_STATE_INCONSISTENT", "Estado gravado da nota inconsistente: " + reason);
        }
    }
}
