package com.moneylog.core.income.domain;

public enum TaxKind {
    INSS(TaxBasis.COMPETENCE),
    IRRF(TaxBasis.PAYMENT_DATE);

    private final TaxBasis basis;

    TaxKind(TaxBasis basis) {
        this.basis = basis;
    }

    public TaxBasis basis() {
        return basis;
    }
}
