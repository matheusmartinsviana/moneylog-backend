package com.moneylog.core.note.exception;

import java.util.UUID;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.exception.NotFoundException;

/** Exceções da feature notas/vault (Etapa 6, ADR-009). A mensagem é só para log e diagnóstico: o front traduz pelo {@code code}. */
public final class NoteExceptions {

    private NoteExceptions() {
    }

    /** Nota inexistente ou de outro usuário (não revela existência). */
    public static class NoteNotFoundException extends NotFoundException {
        public NoteNotFoundException(UUID noteId) {
            super("NOTE_NOT_FOUND", "Nota não encontrada: " + noteId + ".");
        }
    }

    /** Por que um caminho foi recusado; {@link #hostile()} marca as tentativas de escapar da pasta (zip slip, caminho absoluto). */
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

    /** Caminho fora das regras (S-162): vazio, longo, com {@code ..}, absoluto, com {@code \}, controle ou sem {@code .md}. */
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

    /** Já existe nota ativa com este caminho (sem diferenciar maiúsculas). */
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

    /** Vínculo para entidade que não existe ou é de outro usuário. */
    public static class NoteLinkTargetNotFoundException extends BusinessRuleException {
        public NoteLinkTargetNotFoundException(String type, String ref) {
            super("NOTE_LINK_TARGET_NOT_FOUND", "O alvo do vínculo não existe: " + type + " " + ref + ".");
        }
    }

    /** Vínculo mal formado: tipo sem id/chave, chave fora de AAAA-MM, vínculo da nota com ela mesma. */
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

    /**
     * O texto tem mais de {@code max} wikilinks distintos (S-271): recusado em vez de guardar só os primeiros, porque um link fora do
     * índice não aparece nos backlinks e não é reescrito ao renomear. Mesmo código {@code NOTE_LINK_LIMIT_REACHED}; a importação o
     * relata como {@code NOTE_TOO_MANY_LINKS} e segue com as demais notas.
     */
    public static class NoteWikilinkLimitReachedException extends NoteLinkLimitReachedException {
        public NoteWikilinkLimitReachedException(int max) {
            super("Uma nota pode ter no máximo " + max + " wikilinks distintos no texto.");
        }
    }

    /**
     * Renomear/mover uma nota reescreve os wikilinks de OUTRAS notas; se uma delas passaria de 200 KB com o nome novo, a operação
     * inteira é desfeita e o erro cita a nota (S-277). Mesmo código {@code NOTE_TOO_LARGE}.
     */
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

    /** Nota na lixeira não é editada, movida nem vinculada (restaure primeiro). */
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

    /** YAML do frontmatter ilegível, malicioso (tags {@code !!}, âncoras, aninhamento) ou acima de 64 KB (S-166). */
    public static class NoteFrontmatterInvalidException extends BusinessRuleException {
        public NoteFrontmatterInvalidException(String reason) {
            super("NOTE_FRONTMATTER_INVALID", "Frontmatter inválido: " + reason);
        }
    }

    /** A chave {@code moneylog} do frontmatter é reservada aos vínculos (use o campo {@code links}). */
    public static class NoteFrontmatterReservedException extends BusinessRuleException {
        public NoteFrontmatterReservedException() {
            super("NOTE_FRONTMATTER_RESERVED", "A chave 'moneylog' do frontmatter é reservada: use os vínculos da nota.");
        }
    }

    /** Zip ilegível, com caminho hostil ou que não é zip: nada é gravado. */
    public static class VaultImportInvalidException extends BusinessRuleException {
        public VaultImportInvalidException(String reason) {
            super("VAULT_IMPORT_INVALID", "Importação recusada: " + reason);
        }
    }

    /** Zip acima de 20 MB, 2.000 entradas ou 100 MB descomprimidos: nada é gravado. */
    public static class VaultImportTooLargeException extends BusinessRuleException {
        public VaultImportTooLargeException(String reason) {
            super("VAULT_IMPORT_TOO_LARGE", "Importação acima dos limites: " + reason);
        }
    }

    /**
     * O vault não cabe nos limites da importação (5.500 entradas, 300 MB descomprimidos, 20 MB de zip): a exportação recusa em vez de gerar um
     * backup que o próprio sistema não restauraria (S-272). Nada é enviado.
     */
    public static class VaultExportTooLargeException extends BusinessRuleException {
        public VaultExportTooLargeException(String reason) {
            super("EXPORT_TOO_LARGE", "O vault é grande demais para exportar em um zip que a importação aceite: " + reason);
        }
    }

    /** A busca passou do limite de tempo (S-279): o termo é largo demais para o tamanho do vault. */
    public static class NoteSearchTimeoutException extends BusinessRuleException {
        public NoteSearchTimeoutException() {
            super("NOTE_SEARCH_TIMEOUT", "A busca demorou demais. Use um termo mais específico ou filtre por pasta ou tag.");
        }
    }

    /** Estado gravado inconsistente ao reidratar: vira 500 (invariante interna). */
    public static class NoteStateInconsistentException extends DomainException {
        public NoteStateInconsistentException(String reason) {
            super("NOTE_STATE_INCONSISTENT", "Estado gravado da nota inconsistente: " + reason);
        }
    }
}
