package com.moneylog.core.card.domain;

import java.util.Objects;
import java.util.Optional;

import com.moneylog.core.card.exception.CardExceptions.InvoiceDocumentAmountInvalidException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public final class InvoiceDocument {

    private InvoiceDocument() {
    }

    public record Divergence(Money calculated, Money documented, Money difference) {

        public Money absolute() {
            return difference.abs();
        }

        public boolean exceeds(Money tolerance) {
            return absolute().isGreaterThan(tolerance);
        }
    }

    public static Money validated(Money amount) {
        Objects.requireNonNull(amount, "amount");
        if (!CurrencyCode.BRL.equals(amount.currency())) {
            throw new InvoiceDocumentAmountInvalidException("o valor do documento é em BRL.");
        }
        if (amount.isNegative()) {
            throw new InvoiceDocumentAmountInvalidException("não pode ser negativo.");
        }
        return amount;
    }

    public static Optional<Divergence> divergence(Money calculatedTotal, Money documented) {
        if (documented == null) {
            return Optional.empty();
        }
        Money difference = calculatedTotal.subtract(documented);
        return difference.isZero() ? Optional.empty() : Optional.of(new Divergence(calculatedTotal, documented, difference));
    }
}
