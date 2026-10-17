package com.moneylog.core.income.domain;

import java.time.LocalDate;

public record TaxSource(String agency, String norm, String url, LocalDate consultedOn) {
}
