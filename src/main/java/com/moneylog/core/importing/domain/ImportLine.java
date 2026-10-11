package com.moneylog.core.importing.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineException;
import com.moneylog.core.sk.money.Money;

public record ImportLine(UUID sourceId, LocalDate date, Money amount, ImportDirection direction, String description,
                         String fitId) {

    public ImportLine {
        if (sourceId == null) {
            throw new InvalidImportLineException("a conta ou o cartão de origem é obrigatório.");
        }
        if (date == null) {
            throw new InvalidImportLineException("a data é obrigatória.");
        }
        if (amount == null || !amount.isPositive()) {
            throw new InvalidImportLineException("o valor precisa ser positivo (a direção indica entrada ou saída).");
        }
        if (direction == null) {
            throw new InvalidImportLineException("a direção é obrigatória.");
        }
        fitId = fitId == null || fitId.isBlank() ? null : fitId.strip();
    }

    public static ImportLine of(UUID sourceId, LocalDate date, Money amount, ImportDirection direction, String description) {
        return new ImportLine(sourceId, date, amount, direction, description, null);
    }

    public String normalizedDescription() {
        return DescriptionNormalizer.normalize(description);
    }

    public Money signedAmount() {
        return direction == ImportDirection.IN ? amount : amount.negate();
    }
}
