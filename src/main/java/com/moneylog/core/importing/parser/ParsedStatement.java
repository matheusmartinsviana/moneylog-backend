package com.moneylog.core.importing.parser;

import java.time.LocalDate;
import java.util.List;

import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.sk.money.Money;

/**
 * Resultado puro de um parser de extrato (S-169): as linhas lidas e as ignoradas (com o motivo), nunca um centavo a mais ou a menos.
 *
 * @param format     formato detectado
 * @param cardStatement {@code true} se o arquivo se declara extrato de cartão (OFX {@code CREDITCARDMSGSRSV1}); CSV: {@code false}
 * @param rows       linhas válidas, na ordem do arquivo
 * @param skipped    linhas ignoradas (branco, total, valor zero, data inválida...) com o motivo
 */
public record ParsedStatement(Format format, boolean cardStatement, List<Row> rows, List<Skipped> skipped) {

    public enum Format { CSV, OFX }

    public ParsedStatement {
        rows = List.copyOf(rows);
        skipped = List.copyOf(skipped);
    }

    /**
     * @param lineNumber linha do arquivo (1-based)
     * @param amount     valor sempre positivo; o sinal vai em {@code direction}
     * @param fitId      {@code FITID} do OFX, ou {@code null}
     */
    public record Row(int lineNumber, LocalDate date, Money amount, ImportDirection direction, String description, String fitId) {
    }

    public record Skipped(int lineNumber, String reason) {
    }
}
