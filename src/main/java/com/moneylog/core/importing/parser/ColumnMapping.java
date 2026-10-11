package com.moneylog.core.importing.parser;

import com.moneylog.core.importing.exception.ImportingExceptions.ImportMappingInvalidException;

public record ColumnMapping(int date, int description, Integer amount, Integer debit, Integer credit, boolean invert, boolean hasHeader,
                            Character delimiter) {

    public ColumnMapping {
        if (date < 0 || description < 0) {
            throw new ImportMappingInvalidException("as colunas de data e descrição são obrigatórias.");
        }
        boolean single = amount != null;
        boolean dual = debit != null || credit != null;
        if (single == dual) {
            throw new ImportMappingInvalidException("informe a coluna de valor OU as colunas de débito/crédito.");
        }
        if (single && amount < 0 || debit != null && debit < 0 || credit != null && credit < 0) {
            throw new ImportMappingInvalidException("índice de coluna negativo.");
        }
    }
}
