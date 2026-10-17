package com.moneylog.core.income.domain;

import java.time.LocalDate;

public record TaxTableRef(TaxKind kind, int version, LocalDate effectiveFrom) {
}
