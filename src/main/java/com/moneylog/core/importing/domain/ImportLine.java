package com.moneylog.core.importing.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineException;
import com.moneylog.core.sk.money.Money;

/**
 * Linha de extrato já interpretada pelo parser (CSV/OFX, etapa de importação), antes da revisão.
 *
 * @param sourceId    conta ou cartão de onde o extrato veio
 * @param date        data da linha
 * @param amount      valor sempre positivo; o sinal vem de {@code direction}
 * @param direction   entrada ou saída
 * @param description descrição original (pode ser vazia)
 * @param fitId       identificador da transação no OFX ({@code FITID}), ou {@code null}
 */
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

    /** Valor com sinal: positivo para entrada, negativo para saída. */
    public Money signedAmount() {
        return direction == ImportDirection.IN ? amount : amount.negate();
    }
}
