package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.cards.InvoiceView;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListInvoicesUseCase {

    Slice<InvoiceView> handle(ListInvoicesQuery query);

    record ListInvoicesQuery(UUID userId, UUID cardId, LocalDate from, LocalDate to, PageQuery page) {
    }
}
