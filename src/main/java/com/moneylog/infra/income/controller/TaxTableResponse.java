package com.moneylog.infra.income.controller;

import java.time.LocalDate;
import java.util.List;

import com.moneylog.core.income.domain.TaxBasis;
import com.moneylog.core.income.domain.TaxKind;
import com.moneylog.core.income.domain.TaxMethod;
import com.moneylog.core.income.domain.TaxReduction;
import com.moneylog.core.income.domain.TaxTable;
import com.moneylog.core.sk.money.Money;

public record TaxTableResponse(TaxKind kind, int version, LocalDate effectiveFrom, LocalDate effectiveTo, TaxBasis basis,
                               TaxMethod method, List<BracketResponse> brackets, Money contributionCeiling, Money dependentDeduction,
                               Money simplifiedDiscountLimit, ReductionResponse reduction, List<SourceResponse> sources) {

    public record BracketResponse(Money from, Money upTo, int rateBps, Money deduction) {
    }

    public record ReductionResponse(Money fullReductionUpTo, Money fullReductionAmount, Money phaseOutUpTo, Money constant,
                                    String coefficient) {
    }

    public record SourceResponse(String agency, String norm, String url, LocalDate consultedOn) {
    }

    public static TaxTableResponse of(TaxTable t) {
        TaxReduction r = t.reduction();
        return new TaxTableResponse(t.kind(), t.version(), t.effectiveFrom(), t.effectiveTo(), t.basis(), t.method(),
                t.brackets().stream().map(b -> new BracketResponse(b.from(), b.upTo(), b.rateBps(), b.deduction())).toList(),
                t.contributionCeiling(), t.dependentDeduction(), t.simplifiedDiscountLimit(),
                r == null ? null : new ReductionResponse(r.fullReductionUpTo(), r.fullReductionAmount(), r.phaseOutUpTo(), r.constant(),
                        r.coefficientText()),
                t.sources().stream().map(s -> new SourceResponse(s.agency(), s.norm(), s.url(), s.consultedOn())).toList());
    }

    public record Listing(int year, List<TaxTableResponse> items) {
    }
}
