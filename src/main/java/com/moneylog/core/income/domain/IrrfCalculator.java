package com.moneylog.core.income.domain;

import java.util.Objects;

import com.moneylog.core.sk.money.Money;

public final class IrrfCalculator {

    private IrrfCalculator() {
    }

    public static Money calculate(TaxTable table, Money gross, Money inss, int dependents) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(inss, "inss");
        if (table.kind() != TaxKind.IRRF || table.method() != TaxMethod.RATE_MINUS_DEDUCTION) {
            throw new IllegalArgumentException("a tabela não é de IRRF.");
        }
        if (dependents < 0) {
            throw new IllegalArgumentException("dependentes negativos.");
        }
        long dependentDeduction = table.dependentDeduction() == null ? 0L : table.dependentDeduction().cents();
        long legalDeductions = Math.addExact(inss.cents(), Math.multiplyExact(dependentDeduction, (long) dependents));
        long simplified = table.simplifiedDiscountLimit() == null ? 0L : Math.min(table.simplifiedDiscountLimit().cents(), gross.cents());
        long base = Math.max(0L, gross.cents() - Math.max(legalDeductions, simplified));
        long tax = taxOn(table, base);
        long reduction = table.reduction() == null ? 0L : table.reduction().reductionFor(gross).cents();
        return Money.ofCents(Math.max(0L, tax - reduction), gross.currency());
    }

    private static long taxOn(TaxTable table, long base) {
        for (TaxBracket bracket : table.brackets()) {
            if (bracket.upTo() == null || base <= bracket.upTo().cents()) {
                return Math.max(0L, TaxMath.rateOf(base, bracket.rateBps()) - bracket.deduction().cents());
            }
        }
        return 0L;
    }
}
