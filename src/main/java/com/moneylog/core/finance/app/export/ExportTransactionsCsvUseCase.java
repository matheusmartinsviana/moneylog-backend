package com.moneylog.core.finance.app.export;

import java.util.Locale;
import java.util.UUID;

import com.moneylog.core.transaction.app.query.TransactionFilter;

public interface ExportTransactionsCsvUseCase {

    CsvExport handle(ExportTransactionsCsvQuery query);

    record ExportTransactionsCsvQuery(UUID userId, TransactionFilter filter, boolean includeVoided, Locale locale) {
    }
}
