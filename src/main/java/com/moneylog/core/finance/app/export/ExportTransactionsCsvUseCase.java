package com.moneylog.core.finance.app.export;

import java.util.Locale;
import java.util.UUID;

import com.moneylog.core.transaction.app.query.TransactionFilter;

public interface ExportTransactionsCsvUseCase {

    /**
     * CSV de movimentações (S-153): UTF-8 com BOM, separador {@code ;}, valores {@code 1234,50}, datas ISO, cabeçalho no idioma de
     * {@code locale} (padrão português) e <b>uma linha por movimentação</b> (a compra em 6x aparece uma vez, com o valor inteiro).
     * Mesmo filtro da lista; estornadas só com {@code status = VOIDED} ou {@code includeVoided}.
     */
    CsvExport handle(ExportTransactionsCsvQuery query);

    /** @param includeVoided {@code true} = ativas e estornadas (sobrepõe o {@code status} do filtro) */
    record ExportTransactionsCsvQuery(UUID userId, TransactionFilter filter, boolean includeVoided, Locale locale) {
    }
}
