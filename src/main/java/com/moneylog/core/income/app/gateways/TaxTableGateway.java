package com.moneylog.core.income.app.gateways;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.moneylog.core.income.domain.TaxKind;
import com.moneylog.core.income.domain.TaxTable;

public interface TaxTableGateway {

    Optional<TaxTable> findEffective(TaxKind kind, LocalDate reference);

    List<TaxTable> listForYear(int year);
}
