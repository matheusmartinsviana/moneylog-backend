package com.moneylog.core.note.app.usecase;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

public interface ExportVaultUseCase {

    VaultExport handle(ExportVaultQuery query);

    record ExportVaultQuery(UUID userId) {
    }

    interface VaultExport extends AutoCloseable {
        String fileName();

        int noteCount();

        long sizeBytes();

        void writeTo(OutputStream out) throws IOException;

        @Override
        void close();
    }
}
