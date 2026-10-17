package com.moneylog.core.income.domain;

import java.util.Objects;

import com.moneylog.core.sk.money.Money;

public final class InssCalculator {

    private InssCalculator() {
    }

    public static Money calculate(TaxTable table, Money gross) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(gross, "gross");
        if (table.kind() != TaxKind.INSS || table.method() != TaxMethod.PROGRESSIVE_BY_BAND) {
            throw new IllegalArgumentException("a tabela não é de INSS progressivo.");
        }
        long contribution = gross.cents();
        if (table.contributionCeiling() != null) {
            contribution = Math.min(contribution, table.contributionCeiling().cents());
        }
        long lower = 0L;
        long weighted = 0L;
        for (TaxBracket bracket : table.brackets()) {
            if (contribution <= lower) {
                break;
            }
            long upper = bracket.upTo() == null ? contribution : Math.min(bracket.upTo().cents(), contribution);
            weighted = Math.addExact(weighted, Math.multiplyExact(upper - lower, (long) bracket.rateBps()));
            lower = upper;
        }
        return Money.ofCents(TaxMath.divideHalfUp(weighted, 10_000L), gross.currency());
    }
}
