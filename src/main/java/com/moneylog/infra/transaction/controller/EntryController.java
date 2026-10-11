package com.moneylog.infra.transaction.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.transactions.usecase.ListEntriesUseCase;
import com.moneylog.core.finance.app.transactions.usecase.ListEntriesUseCase.ListEntriesQuery;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.app.query.EntryFilter;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.security.CurrentUser;

@RestController
@RequestMapping("/v1/entries")
public class EntryController {

    private final ListEntriesUseCase list;

    public EntryController(ListEntriesUseCase list) {
        this.list = list;
    }

    @GetMapping
    public EntryResponse list(@CurrentUser UUID userId, @RequestParam String competence,
                              @RequestParam(required = false) EntryLens lens, @RequestParam(required = false) UUID categoryId,
                              @RequestParam(required = false) UUID accountId, @RequestParam(required = false) PaymentMethod method,
                              PageQuery page) {
        Competence parsed;
        try {
            parsed = Competence.parse(competence);
        } catch (RuntimeException e) {
            throw new RequestValidationException("competence", "INVALID_FORMAT", "'competence' deve ser YYYY-MM.");
        }
        EntryFilter filter = new EntryFilter(categoryId, accountId, method);
        return EntryResponse.of(list.handle(new ListEntriesQuery(userId, parsed, lens == null ? EntryLens.CONSUMPTION : lens, filter, page)));
    }
}
