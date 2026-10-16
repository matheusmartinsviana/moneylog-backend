package com.moneylog.infra.income.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.moneylog.core.income.app.usecase.ListTaxTablesUseCase;
import com.moneylog.core.income.app.usecase.ListTaxTablesUseCase.ListTaxTablesQuery;

@RestController
@RequestMapping("/v1/tax-tables")
@Validated
public class TaxTableController {

    private final ListTaxTablesUseCase list;

    public TaxTableController(ListTaxTablesUseCase list) {
        this.list = list;
    }

    @GetMapping
    public TaxTableResponse.Listing list(@RequestParam @Min(2000) @Max(2100) int year) {
        return new TaxTableResponse.Listing(year, list.handle(new ListTaxTablesQuery(year)).stream().map(TaxTableResponse::of).toList());
    }
}
