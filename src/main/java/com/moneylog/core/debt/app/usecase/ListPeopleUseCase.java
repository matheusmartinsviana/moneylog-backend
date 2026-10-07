package com.moneylog.core.debt.app.usecase;

import java.util.UUID;

import com.moneylog.core.debt.app.PersonView;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListPeopleUseCase {

    /**
     * Ordem {@code name asc}. {@code archived} nulo = todas; {@code query} filtra o nome sem diferenciar caixa nem acentos.
     * O saldo de cada pessoa sai das dívidas lidas de uma vez (S-142).
     */
    Slice<PersonView> handle(ListPeopleQuery query);

    record ListPeopleQuery(UUID userId, Boolean archived, String query, PageQuery page) {
    }
}
