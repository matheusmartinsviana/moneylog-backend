package com.moneylog.core.note.app.usecase;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

public interface ExportVaultUseCase {

    /**
     * Exporta as notas ativas (a lixeira não vai) como um zip compatível com o Obsidian (S-157): um {@code .md} por nota, no caminho dela,
     * com o frontmatter e, nos vínculos explícitos, {@code moneylog.links} (nota da lixeira com {@code state: trashed}). O zip é montado em
     * lotes num arquivo temporário privado <b>antes</b> de o download começar: se o vault não cabe nos limites da importação (5.500
     * entradas, 300 MB descomprimidos, 20 MB de zip) responde {@code 422 EXPORT_TOO_LARGE}, e qualquer falha na montagem é um erro normal
     * (nunca um zip cortado com cabeçalho 200). O round-trip fecha: o que a exportação entrega, a importação aceita.
     */
    VaultExport handle(ExportVaultQuery query);

    record ExportVaultQuery(UUID userId) {
    }

    /** O zip pronto; o nome do arquivo usa o dia do usuário. {@link #close()} apaga o arquivo temporário. */
    interface VaultExport extends AutoCloseable {
        String fileName();

        int noteCount();

        /** Tamanho do zip em bytes (vai no {@code Content-Length}: o cliente detecta um download cortado). */
        long sizeBytes();

        void writeTo(OutputStream out) throws IOException;

        @Override
        void close();
    }
}
