package com.moneylog.core.income.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.moneylog.core.sk.money.Money;

public record TaxTable(TaxKind kind, int version, LocalDate effectiveFrom, LocalDate effectiveTo, TaxBasis basis, TaxMethod method,
                       List<TaxBracket> brackets, Money contributionCeiling, Money dependentDeduction, Money simplifiedDiscountLimit,
                       TaxReduction reduction, List<TaxSource> sources) {

    public TaxTable {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(method, "method");
        if (version < 1 || basis != kind.basis() || (effectiveTo != null && effectiveTo.isBefore(effectiveFrom))) {
            throw new IllegalArgumentException("tabela fiscal inconsistente.");
        }
        if (brackets == null || brackets.isEmpty()) {
            throw new IllegalArgumentException("a tabela precisa de faixas.");
        }
        brackets = List.copyOf(brackets);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public boolean isEffectiveOn(LocalDate reference) {
        return !reference.isBefore(effectiveFrom) && (effectiveTo == null || !reference.isAfter(effectiveTo));
    }

    public TaxTableRef ref() {
        return new TaxTableRef(kind, version, effectiveFrom);
    }
}
