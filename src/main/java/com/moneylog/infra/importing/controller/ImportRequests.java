package com.moneylog.infra.importing.controller;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.Action;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportMappingInvalidException;
import com.moneylog.core.importing.parser.ColumnMapping;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Corpos de entrada de {@code /v1/imports} (contrato: {@code ImportLinePatch}, {@code ImportBulkInput}, {@code ImportMapping}, {@code ImportUndoInput}). */
public final class ImportRequests {

    private ImportRequests() {
    }

    /** {@code asTransfer} só vale em conta, em linha marcada {@code POSSIBLE_TRANSFER}. */
    public record AsTransfer(@NotNull UUID counterpartAccountId) {
    }

    public record LinePatch(@NotNull Action action, UUID categoryId, PaymentMethod method, @Size(max = 200) @NoControlChars String description,
                            @Valid AsTransfer asTransfer, Boolean learn) {
    }

    public record BulkInput(@NotEmpty @Size(max = 500) List<@NotNull UUID> lineIds, @NotNull Action action) {
    }

    public record UndoInput(@Size(max = 300) @NoControlChars String reason) {
    }

    /** Índices de coluna 0-based; valor em uma coluna ({@code amount}) ou duas ({@code debit}/{@code credit}). */
    public record MappingInput(@NotNull Integer date, @NotNull Integer description, Integer amount, Integer debit, Integer credit,
                               Boolean invert, Boolean hasHeader, @Size(min = 1, max = 1) String delimiter) {

        public ColumnMapping toDomain() {
            if (date == null || description == null) {
                throw new ImportMappingInvalidException("as colunas de data e descrição são obrigatórias.");
            }
            if (!List.of(";", ",", "\t").contains(delimiter == null ? ";" : delimiter)) {
                throw new ImportMappingInvalidException("o delimitador deve ser ';', ',' ou tab.");
            }
            return new ColumnMapping(date, description, amount, debit, credit, Boolean.TRUE.equals(invert), hasHeader == null || hasHeader,
                    delimiter == null ? null : delimiter.charAt(0));
        }

        public static MappingInput of(ColumnMapping m) {
            return new MappingInput(m.date(), m.description(), m.amount(), m.debit(), m.credit(), m.invert(), m.hasHeader(),
                    m.delimiter() == null ? null : String.valueOf(m.delimiter()));
        }
    }
}
