package com.moneylog.core.debt.app.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.PersonView;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListPeopleUseCase {

    Slice<PersonView> handle(ListPeopleQuery query);

    record ListPeopleQuery(UUID userId, Boolean archived, String query, PageQuery page) {
    }
}
