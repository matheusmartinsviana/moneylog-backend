package com.moneylog.core.note.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.note.app.vault.ZipSource;

public interface ImportVaultUseCase {

    ImportReport handle(ImportVaultCommand command);

    enum ConflictMode { SKIP, RENAME }

    record ImportVaultCommand(UUID userId, ZipSource zip, ConflictMode mode) {
    }

    record RenamedFile(String from, String to) {
    }

    record ImportWarning(String path, String code, String message) {
    }

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
