package com.moneylog.core.note.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.note.app.vault.ZipSource;

public interface ImportVaultUseCase {

    /**
     * Importa um zip de notas Markdown (S-157, S-162), tudo ou nada, em uma transação. Só {@code .md} entra; o resto é ignorado e listado.
     * Zip hostil ({@code ..}, caminho absoluto, {@code \}), corrompido, acima de 20 MB, 2.000 entradas ou 100 MB descomprimidos →
     * {@code VAULT_IMPORT_INVALID}/{@code VAULT_IMPORT_TOO_LARGE}, e nada é gravado. Conflito de caminho: {@code SKIP} pula, {@code RENAME}
     * cria {@code nome (2).md}. Frontmatter inválido: a nota entra sem ele, com aviso. Vínculo para entidade inexistente vira pendente.
     */
    ImportReport handle(ImportVaultCommand command);

    enum ConflictMode { SKIP, RENAME }

    record ImportVaultCommand(UUID userId, ZipSource zip, ConflictMode mode) {
    }

    record RenamedFile(String from, String to) {
    }

    record ImportWarning(String path, String code, String message) {
    }

    /** O que a importação fez. {@code pendingLinks} conta wikilinks sem nota e vínculos para entidades que não existem, das notas criadas. */
    record ImportReport(int created, int skipped, int pendingLinks, List<String> ignoredFiles, List<String> skippedFiles,
                        List<RenamedFile> renamedFiles, List<ImportWarning> warnings) {
        public ImportReport {
            ignoredFiles = List.copyOf(ignoredFiles);
            skippedFiles = List.copyOf(skippedFiles);
            renamedFiles = List.copyOf(renamedFiles);
            warnings = List.copyOf(warnings);
        }
    }
}
