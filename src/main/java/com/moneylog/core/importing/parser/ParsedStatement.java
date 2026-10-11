package com.moneylog.core.importing.parser;

import java.time.LocalDate;
import java.util.List;

import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.sk.money.Money;

public record ParsedStatement(Format format, boolean cardStatement, List<Row> rows, List<Skipped> skipped) {

    public enum Format { CSV, OFX }

    public ParsedStatement {
        rows = List.copyOf(rows);
        skipped = List.copyOf(skipped);
    }

    public record Row(int lineNumber, LocalDate date, Money amount, ImportDirection direction, String description, String fitId) {
    }

    public record Skipped(int lineNumber, String reason) {
    }
}
