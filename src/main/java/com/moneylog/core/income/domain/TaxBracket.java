package com.moneylog.core.income.domain;

import java.util.Objects;

import com.moneylog.core.sk.money.Money;

public record TaxBracket(Money from, Money upTo, int rateBps, Money deduction) {

    public TaxBracket {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(deduction, "deduction");
        if (rateBps < 0 || rateBps > 10_000) {
            throw new IllegalArgumentException("alíquota fora de 0..10000 bps: " + rateBps);
        }
        if (from.isNegative() || deduction.isNegative() || (upTo != null && upTo.isLessThan(from))) {
            throw new IllegalArgumentException("faixa inválida.");
        }
    }
}
